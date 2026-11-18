/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.junit.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.junit.Assert.*;

/**
 * Unit tests for SpEL's {@code matches} operator, with an emphasis on the
 * bounded pattern cache and on the maximum regular expression length.
 *
 * @see OperatorMatches
 */
public class OperatorMatchesTests {

	private static final int MAX_REGEX_LENGTH = 1000;

	private final SpelExpressionParser parser = new SpelExpressionParser();


	@Test
	public void patternCacheSizeIsBounded() {
		assertEquals(256, OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void patternCacheDoesNotGrowBeyondTheMaximumCacheSize() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		// Feed the operator far more distinct regular expressions than the cache
		// is allowed to hold: prior to the fix, every one of them was retained in
		// an unbounded ConcurrentHashMap.
		int distinctRegexCount = OperatorMatches.MAX_PATTERN_CACHE_SIZE * 4;
		for (int i = 0; i < distinctRegexCount; i++) {
			context.setVariable("regex", distinctRegex(i));
			assertTrue(expression.getValue(context, Boolean.class));
		}

		Object patternCache = patternCache(expression);
		assertEquals(OperatorMatches.MAX_PATTERN_CACHE_SIZE, sizeLimit(patternCache));
		assertTrue(size(patternCache) <= OperatorMatches.MAX_PATTERN_CACHE_SIZE);

		// Evicted patterns are transparently recompiled, so results stay correct.
		context.setVariable("regex", distinctRegex(0));
		assertTrue(expression.getValue(context, Boolean.class));
		context.setVariable("regex", "does-not-match");
		assertFalse(expression.getValue(context, Boolean.class));
		assertTrue(size(patternCache) <= OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void regexExceedingMaxLengthIsRejectedBeforeTheCacheIsConsulted() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		String tooLongRegex = "abc|" + repeat("x", MAX_REGEX_LENGTH);
		assertTrue(tooLongRegex.length() > MAX_REGEX_LENGTH);
		context.setVariable("regex", tooLongRegex);

		// A regex that has never been seen before must be rejected...
		assertMaxRegexLengthExceeded(expression, context);
		// ...and it must still be rejected when evaluated again, which proves the
		// length check runs before the cache lookup and that the oversized regex
		// was never admitted to the cache.
		assertMaxRegexLengthExceeded(expression, context);
		assertEquals(0, size(patternCache(expression)));

		// The same holds once the cache has already been populated with valid patterns.
		context.setVariable("regex", "abc");
		assertTrue(expression.getValue(context, Boolean.class));
		assertEquals(1, size(patternCache(expression)));

		context.setVariable("regex", tooLongRegex + "y");
		assertMaxRegexLengthExceeded(expression, context);
		assertMaxRegexLengthExceeded(expression, context);
		assertEquals(1, size(patternCache(expression)));
	}

	@Test
	public void regexOfMaxLengthIsStillAccepted() {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		String regex = "abc|" + repeat("x", MAX_REGEX_LENGTH - 4);
		assertEquals(MAX_REGEX_LENGTH, regex.length());
		context.setVariable("regex", regex);

		assertTrue(expression.getValue(context, Boolean.class));
	}

	@Test
	public void invalidRegexIsRejectedAndNeverCached() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", "abc(");

		for (int i = 0; i < 2; i++) {
			try {
				expression.getValue(context, Boolean.class);
				fail("Should have rejected an invalid pattern");
			}
			catch (SpelEvaluationException ex) {
				assertEquals(SpelMessage.INVALID_PATTERN, ex.getMessageCode());
			}
		}
		assertEquals(0, size(patternCache(expression)));
	}

	@Test
	public void matchesEvaluatesPositivelyAndNegatively() {
		StandardEvaluationContext context = new StandardEvaluationContext();
		assertTrue(parser.parseExpression("'5.00' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class));
		assertFalse(parser.parseExpression("'5.0067' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class));
	}

	@Test
	public void concurrentMapConstructorIgnoresSuppliedMapButStillEvaluates() throws Exception {
		ConcurrentMap<String, Pattern> suppliedCache = new ConcurrentHashMap<String, Pattern>();
		OperatorMatches operator = new OperatorMatches(suppliedCache, 1,
				stringLiteral("'5.00'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertEquals(Boolean.TRUE, operator.getValueInternal(state).getValue());

		// The supplied unbounded map is no longer used at all.
		assertTrue(suppliedCache.isEmpty());
		assertEquals(OperatorMatches.MAX_PATTERN_CACHE_SIZE, sizeLimit(patternCache(operator)));
		assertEquals(1, size(patternCache(operator)));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void deprecatedNoCacheConstructorStillEvaluates() throws Exception {
		OperatorMatches operator = new OperatorMatches(1,
				stringLiteral("'5.0067'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertEquals(Boolean.FALSE, operator.getValueInternal(state).getValue());
		assertEquals(OperatorMatches.MAX_PATTERN_CACHE_SIZE, sizeLimit(patternCache(operator)));
	}


	private SpelExpression parseMatchesExpression() {
		return (SpelExpression) this.parser.parseExpression("#input matches #regex");
	}

	private static void assertMaxRegexLengthExceeded(SpelExpression expression, StandardEvaluationContext context) {
		try {
			expression.getValue(context, Boolean.class);
			fail("Should have exceeded the maximum regex length");
		}
		catch (SpelEvaluationException ex) {
			assertEquals(SpelMessage.MAX_REGEX_LENGTH_EXCEEDED, ex.getMessageCode());
		}
	}

	private static String distinctRegex(int index) {
		// Always matches "abc", but the regex text itself is unique per index.
		return "abc|no-match-" + index;
	}

	private static StringLiteral stringLiteral(String quotedValue) {
		return new StringLiteral(quotedValue, 1, quotedValue);
	}

	private static Object patternCache(SpelExpression expression) throws Exception {
		return patternCache((OperatorMatches) expression.getAST());
	}

	private static Object patternCache(OperatorMatches operator) throws Exception {
		Field field = OperatorMatches.class.getDeclaredField("patternCache");
		field.setAccessible(true);
		return field.get(operator);
	}

	private static int size(Object patternCache) throws Exception {
		return invokeIntMethod(patternCache, "size");
	}

	private static int sizeLimit(Object patternCache) throws Exception {
		return invokeIntMethod(patternCache, "sizeLimit");
	}

	private static int invokeIntMethod(Object target, String methodName) throws Exception {
		Method method = target.getClass().getDeclaredMethod(methodName);
		method.setAccessible(true);
		return (Integer) method.invoke(target);
	}

	private static String repeat(String str, int count) {
		StringBuilder sb = new StringBuilder(str.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(str);
		}
		return sb.toString();
	}

}
