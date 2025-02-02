/*
 * Copyright 2011 The nanojson Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.grack.nanojson;

import ch.randelshofer.fastdoubleparser.JavaBigIntegerParser;
import ch.randelshofer.fastdoubleparser.JavaDoubleParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.util.Arrays;

/**
 * Simple JSON parser.
 * 
 * <pre>
 * Object json = {@link JsonParser}.any().from("{\"a\":[true,false], \"b\":1}");
 * Number json = ({@link Number}){@link JsonParser}.any().from("123.456e7");
 * JsonObject json = {@link JsonParser}.object().from("{\"a\":[true,false], \"b\":1}");
 * JsonArray json = {@link JsonParser}.array().from("[1, {\"a\":[true,false], \"b\":1}]");
 * </pre>
 */
public final class JsonParser {
	private Object value;
	private int token;

	private final JsonTokener tokener;
	private final boolean lazyNumbers;
	private final boolean lazyStrings;

	/**
	 * Returns a type-safe parser context for a {@link JsonObject}, {@link JsonArray} or "any" type from which you can
	 * parse a {@link String} or a {@link Reader}.
	 *
	 * @param <T> The parsed type.
	 */
	public static final class JsonParserContext<T> {
		private final Class<T> clazz;
		private boolean lazyNumbers = true;
		private boolean lazyStrings = true;

		JsonParserContext(Class<T> clazz) {
			this.clazz = clazz;
		}

		/**
		 * Parses numbers lazily, allowing us to defer some of the cost of
		 * number construction until later.
		 */
		public JsonParserContext<T> withLazyNumbers() {
			lazyNumbers = true;
			return this;
		}

		/**
		 * Parses Strings lazily, allowing us to defer some of the cost of String
		 * construction until later.
		 */
		public JsonParserContext<T> withLazyStrings() {
			lazyStrings = true;
			return this;
		}

		/**
		 * Parses the current JSON type from a {@link String}.
		 */
		public T from(String s) throws JsonParserException {
			return new JsonParser(new JsonTokener(new StringReader(s)), lazyNumbers, lazyStrings).parse(clazz);
		}

		/**
		 * Parses the current` JSON type from a {@link Reader}.
		 */
		public T from(Reader r) throws JsonParserException {
			return new JsonParser(new JsonTokener(r), lazyNumbers, lazyStrings).parse(clazz);
		}

		/**
		 * Parses the current JSON type from a {@link URL}.
		 */
		public T from(URL url) throws JsonParserException {
			try {
				try (InputStream stm = url.openStream()) {
					return from(stm);
				}
			} catch (IOException e) {
				throw new JsonParserException(e, "IOException opening URL", 1, 1, 0);
			}
		}

		/**
		 * Parses the current JSON type from a {@link InputStream}. Detects the encoding from the input stream.
		 */
		public T from(InputStream stm) throws JsonParserException {
			return new JsonParser(new JsonTokener(stm), lazyNumbers, lazyStrings).parse(clazz);
		}
	}

	JsonParser(JsonTokener tokener, boolean lazyNumbers, boolean lazyStrings) throws JsonParserException {
		this.tokener = tokener;
		this.lazyNumbers = lazyNumbers;
		this.lazyStrings = lazyStrings;
	}

	/**
	 * Parses a {@link JsonObject} from a source.
	 * 
	 * <pre>
	 * JsonObject json = {@link JsonParser}.object().from("{\"a\":[true,false], \"b\":1}");
	 * </pre>
	 */
	public static JsonParserContext<JsonObject> object() {
		return new JsonParserContext<>(JsonObject.class);
	}

	/**
	 * Parses a {@link JsonArray} from a source.
	 * 
	 * <pre>
	 * JsonArray json = {@link JsonParser}.array().from("[1, {\"a\":[true,false], \"b\":1}]");
	 * </pre>
	 */
	public static JsonParserContext<JsonArray> array() {
		return new JsonParserContext<>(JsonArray.class);
	}

	/**
	 * Parses any object from a source. For any valid JSON, returns either a null (for the JSON string 'null'), a
	 * {@link String}, a {@link Number}, a {@link Boolean}, a {@link JsonObject} or a {@link JsonArray}.
	 * 
	 * <pre>
	 * Object json = {@link JsonParser}.any().from("{\"a\":[true,false], \"b\":1}");
	 * Number json = ({@link Number}){@link JsonParser}.any().from("123.456e7");
	 * </pre>
	 */
	public static JsonParserContext<Object> any() {
		return new JsonParserContext<>(Object.class);
	}

	/**
	 * Parse a single JSON value from the string, expecting an EOF at the end.
	 */
	<T> T parse(Class<T> clazz) throws JsonParserException {
		advanceToken();
		Object parsed = currentValue();
		if (advanceToken() != JsonTokener.TOKEN_EOF)
			throw tokener.createParseException(null, "Expected end of input, got " + token, true);
		if (clazz != Object.class && (parsed == null || !clazz.isAssignableFrom(parsed.getClass())))
			throw tokener.createParseException(null,
					"JSON did not contain the correct type, expected " + clazz.getSimpleName() + ".", 
					true);
		return clazz.cast(parsed);
	}

	/**
	 * Starts parsing a JSON value at the current token position.
	 */
	private Object currentValue() throws JsonParserException {
		// Only a value start token should appear when we're in the context of parsing a JSON value
		if (token >= JsonTokener.TOKEN_VALUE_MIN)
			return value;
		throw tokener.createParseException(null, "Expected JSON value, got " + token, true);
	}

	/**
	 * Consumes a token, first eating up any whitespace ahead of it. Note that number tokens are not necessarily valid
	 * numbers.
	 */
	private int advanceToken() throws JsonParserException {
		token = tokener.advanceToToken();
		switch (token) {
		case JsonTokener.TOKEN_ARRAY_START: // Inlined function to avoid additional stack
			JsonArray list = new JsonArray();
			if (advanceToken() != JsonTokener.TOKEN_ARRAY_END)
				while (true) {
					list.add(currentValue());
					if (advanceToken() == JsonTokener.TOKEN_ARRAY_END)
						break;
					if (token != JsonTokener.TOKEN_COMMA)
						throw tokener.createParseException(null,
								"Expected a comma or end of the array instead of " + token, true);
					if (advanceToken() == JsonTokener.TOKEN_ARRAY_END)
						throw tokener.createParseException(null, "Trailing comma found in array", true);
				}
			value = list;
			return token = JsonTokener.TOKEN_ARRAY_START;
		case JsonTokener.TOKEN_OBJECT_START: // Inlined function to avoid additional stack
			JsonObject map = new JsonObject();
			if (advanceToken() != JsonTokener.TOKEN_OBJECT_END)
				while (true) {
					if (token != JsonTokener.TOKEN_STRING)
						throw tokener.createParseException(null, "Expected STRING, got " + token, true);
					String key = lazyStrings ? value.toString() : (String) value;
					if (advanceToken() != JsonTokener.TOKEN_COLON)
						throw tokener.createParseException(null, "Expected COLON, got " + token, true);
					advanceToken();
					map.put(key, currentValue());
					if (advanceToken() == JsonTokener.TOKEN_OBJECT_END)
						break;
					if (token != JsonTokener.TOKEN_COMMA)
						throw tokener.createParseException(null,
								"Expected a comma or end of the object instead of " + token, true);
					if (advanceToken() == JsonTokener.TOKEN_OBJECT_END)
						throw tokener.createParseException(null, "Trailing object found in array", true);
				}
			value = map;
			return token = JsonTokener.TOKEN_OBJECT_START;
		case JsonTokener.TOKEN_TRUE:
			value = Boolean.TRUE;
			break;
		case JsonTokener.TOKEN_FALSE:
			value = Boolean.FALSE;
			break;
		case JsonTokener.TOKEN_NULL:
			value = null;
			break;
		case JsonTokener.TOKEN_STRING:
			char[] chars = tokener.reusableBuffer.array();
			chars = Arrays.copyOf(chars, tokener.reusableBuffer.position());
			value = lazyStrings ? new LazyString(chars) : new String(chars);
			break;
		case JsonTokener.TOKEN_NUMBER:
			if (lazyNumbers) {
				chars = tokener.reusableBuffer.array();
				chars = Arrays.copyOf(chars, tokener.reusableBuffer.position());
				value = new JsonLazyNumber(chars, tokener.isDouble);
			} else {
				value = parseNumber();
			}
			break;
		default:
		}

		return token;
	}

	private Number parseNumber() throws JsonParserException {
		char[] number = tokener.reusableBuffer.array();
		number = Arrays.copyOf(number, tokener.reusableBuffer.position());
		int numLength = number.length;

		try {
			if (tokener.isDouble)
				return JavaDoubleParser.parseDouble(number);

			// Quick parse for single-digits
			if (numLength == 1) {
				return number[0] - '0';
			} else if (numLength == 2 && number[0] == '-') {
				return '0' - number[1];
			}

			// HACK: Attempt to parse using the approximate best type for this
			boolean firstMinus = number[0] == '-';
			int length = firstMinus ? numLength - 1 : numLength;
			// CHECKSTYLE_OFF: MagicNumber
			if (length < 10 || (length == 10 && number[firstMinus ? 1 : 0] < '2')) // 2 147 483 647
				return Integer.parseInt(new String(number));
			if (length < 19 || (length == 19 && number[firstMinus ? 1 : 0] < '9')) // 9 223 372 036 854 775 807
				return Long.parseLong(new String(number));
			// CHECKSTYLE_ON: MagicNumber
			return JavaBigIntegerParser.parseBigInteger(number);
		} catch (NumberFormatException e) {
			throw tokener.createParseException(e, "Malformed number: " + new String(number), true);
		}
	}
}
