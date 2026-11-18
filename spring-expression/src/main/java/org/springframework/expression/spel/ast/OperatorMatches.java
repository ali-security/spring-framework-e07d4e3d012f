/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.support.BooleanTypedValue;

/**
 * Implements the matches operator. Matches takes two operands:
 * The first is a String and the second is a Java regex.
 * It will return {@code true} when {@link #getValue} is called
 * if the first operand matches the regex.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @since 3.0
 */
public class OperatorMatches extends Operator {

	/**
	 * Maximum number of compiled regular expressions in the pattern cache: {@value}.
	 */
	public static final int MAX_PATTERN_CACHE_SIZE = 256;

	/**
	 * Maximum number of characters permitted in a regular expression.
	 */
	private static final int MAX_REGEX_LENGTH = 1000;

	private final PatternCache patternCache;


	/**
	 * Create a new {@link OperatorMatches} instance.
	 * <p>Prefer {@link #OperatorMatches(ConcurrentMap, int, SpelNodeImpl...)}
	 * with a shared pattern cache instead.
	 */
	public OperatorMatches(int pos, SpelNodeImpl... operands) {
		this(new PatternCache(MAX_PATTERN_CACHE_SIZE), pos, operands);
	}

	/**
	 * Create a new {@link OperatorMatches} instance with a shared pattern cache.
	 * <p>The supplied {@code patternCacheMap} is ignored: the operator always compiles
	 * through its own pattern cache, bounded to {@value #MAX_PATTERN_CACHE_SIZE}
	 * entries, so that an unbounded shared map cannot be grown without limit.
	 */
	public OperatorMatches(ConcurrentMap<String, Pattern> patternCacheMap, int pos, SpelNodeImpl... operands) {
		this(new PatternCache(MAX_PATTERN_CACHE_SIZE), pos, operands);
	}

	private OperatorMatches(PatternCache patternCache, int pos, SpelNodeImpl... operands) {
		super("matches", pos, operands);
		this.patternCache = patternCache;
	}


	/**
	 * Check the first operand matches the regex specified as the second operand.
	 * @param state the expression state
	 * @return {@code true} if the first operand matches the regex specified as the
	 * second operand, otherwise {@code false}
	 * @throws EvaluationException if there is a problem evaluating the expression
	 * (e.g. the regex is invalid)
	 */
	@Override
	public BooleanTypedValue getValueInternal(ExpressionState state) throws EvaluationException {
		SpelNodeImpl leftOp = getLeftOperand();
		SpelNodeImpl rightOp = getRightOperand();
		Object left = leftOp.getValue(state, String.class);
		Object right = getRightOperand().getValueInternal(state).getValue();

		if (!(left instanceof String)) {
			throw new SpelEvaluationException(leftOp.getStartPosition(),
					SpelMessage.INVALID_FIRST_OPERAND_FOR_MATCHES_OPERATOR, left);
		}
		if (!(right instanceof String)) {
			throw new SpelEvaluationException(rightOp.getStartPosition(),
					SpelMessage.INVALID_SECOND_OPERAND_FOR_MATCHES_OPERATOR, right);
		}
		String rightString = (String) right;
		if (rightString.length() > MAX_REGEX_LENGTH) {
			throw new SpelEvaluationException(getStartPosition(),
					SpelMessage.MAX_REGEX_LENGTH_EXCEEDED, MAX_REGEX_LENGTH);
		}

		state.trackOperation();
		try {
			String leftString = (String) left;
			Pattern pattern = this.patternCache.get(rightString);
			Matcher matcher = pattern.matcher(leftString);
			return BooleanTypedValue.forValue(matcher.matches());
		}
		catch (PatternSyntaxException ex) {
			throw new SpelEvaluationException(rightOp.getStartPosition(), ex, SpelMessage.INVALID_PATTERN, right);
		}
	}


	/**
	 * Thread-safe cache of compiled regular expressions, bounded to a maximum
	 * number of entries and evicting the least recently used pattern first.
	 * <p>Patterns that fail to compile are never cached: the corresponding
	 * {@link PatternSyntaxException} is propagated to the caller.
	 */
	private static class PatternCache {

		private final int sizeLimit;

		// Access-ordered: iteration starts with the least recently used entry
		private final Map<String, Pattern> cache = new LinkedHashMap<String, Pattern>(16, 0.75f, true);

		PatternCache(int sizeLimit) {
			this.sizeLimit = sizeLimit;
		}

		public Pattern get(String regex) {
			synchronized (this.cache) {
				Pattern pattern = this.cache.get(regex);
				if (pattern != null) {
					return pattern;
				}
			}
			Pattern pattern = Pattern.compile(regex);
			synchronized (this.cache) {
				this.cache.put(regex, pattern);
				Iterator<String> keys = this.cache.keySet().iterator();
				while (this.cache.size() > this.sizeLimit && keys.hasNext()) {
					keys.next();
					keys.remove();
				}
			}
			return pattern;
		}

		public int size() {
			synchronized (this.cache) {
				return this.cache.size();
			}
		}

		public int sizeLimit() {
			return this.sizeLimit;
		}
	}

}
