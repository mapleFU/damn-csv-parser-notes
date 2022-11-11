/*******************************************************************************
 * Copyright 2014 Univocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.csv;

import com.univocity.parsers.common.*;
import com.univocity.parsers.common.input.EOFException;
import com.univocity.parsers.common.input.*;

import java.io.*;

import static com.univocity.parsers.csv.UnescapedQuoteHandling.*;

/**
 * A very fast CSV parser implementation.
 *
 * @author Univocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 * @see CsvFormat
 * @see CsvParserSettings
 * @see CsvWriter
 * @see AbstractParser
 *
 * prev 感觉是在 quote 内部处理 quote 使用的
 */
public final class CsvParser extends AbstractParser<CsvParserSettings> {

	private boolean parseUnescapedQuotes;
	private boolean parseUnescapedQuotesUntilDelimiter;
	private boolean backToDelimiter;
	private final boolean doNotEscapeUnquotedValues; // 不对 unquote
	private final boolean keepEscape;
	private final boolean keepQuotes;

	// 下面这两个是解析时候的上下文.
	// unescaped: 正在处理 unescaped quote
	private boolean unescaped;
	// scanner 的上一个字符.
	private char prev;

	private char delimiter;
	private char[] multiDelimiter;

	private char quote; // 比如 " 是 quote, quoteEscape 可能是 ", "" 表示 quote 被 escape; 如果 quote 和 escape 不一样, escapeEscape 表示 escape 如何被处理
	private char quoteEscape;
	private char escapeEscape;

	private char newLine; // 切新一行的 \n

	private final DefaultCharAppender whitespaceAppender;
	private final boolean normalizeLineEndingsInQuotes;
	private UnescapedQuoteHandling quoteHandling;
	private final String nullValue;
	private final int maxColumnLength;
	private final String emptyValue;
	private final boolean trimQuotedLeading;
	private final boolean trimQuotedTrailing;
	private char[] delimiters; // delimiter, multiDelimiter 可以被视为配置, delimiters 是实际的
	private int match = 0;
	private int formatDetectorRowSampleCount;

	/**
	 * The CsvParser supports all settings provided by {@link CsvParserSettings}, and requires this configuration to be properly initialized.
	 *
	 * @param settings the parser configuration
	 */
	public CsvParser(CsvParserSettings settings) {
		super(settings);
		parseUnescapedQuotes = settings.isParseUnescapedQuotes();
		parseUnescapedQuotesUntilDelimiter = settings.isParseUnescapedQuotesUntilDelimiter();
		doNotEscapeUnquotedValues = !settings.isEscapeUnquotedValues();
		keepEscape = settings.isKeepEscapeSequences();
		keepQuotes = settings.getKeepQuotes();
		normalizeLineEndingsInQuotes = settings.isNormalizeLineEndingsWithinQuotes();
		nullValue = settings.getNullValue();
		emptyValue = settings.getEmptyValue();
		maxColumnLength = settings.getMaxCharsPerColumn();
		trimQuotedTrailing = settings.getIgnoreTrailingWhitespacesInQuotes();
		trimQuotedLeading = settings.getIgnoreLeadingWhitespacesInQuotes();
		formatDetectorRowSampleCount = settings.getFormatDetectorRowSampleCount();
		updateFormat(settings.getFormat());

		whitespaceAppender = new ExpandingCharAppender(10, "", whitespaceRangeStart);

		this.quoteHandling = settings.getUnescapedQuoteHandling();
		if (quoteHandling == null) {
			if (parseUnescapedQuotes) {
				if (parseUnescapedQuotesUntilDelimiter) {
					quoteHandling = STOP_AT_DELIMITER;
				} else {
					quoteHandling = STOP_AT_CLOSING_QUOTE;
				}
			} else {
				quoteHandling = RAISE_ERROR;
			}
		} else {
			backToDelimiter = quoteHandling == BACK_TO_DELIMITER;
			parseUnescapedQuotesUntilDelimiter = quoteHandling == STOP_AT_DELIMITER || quoteHandling == SKIP_VALUE || backToDelimiter;
			parseUnescapedQuotes = quoteHandling != RAISE_ERROR;
		}
	}


	@Override
	protected final void parseRecord() {
		// 按照是否有 multiDelimiter, 来解析单行 / 多行
		if (multiDelimiter == null) {
			parseSingleDelimiterRecord();
		} else {
			parseMultiDelimiterRecord();
		}
	}

	// 解析单行的记录
	private final void parseSingleDelimiterRecord() {
		// 跳掉 whiteSpace, 这个应该是行 leading?
		if (ch <= ' ' && ignoreLeadingWhitespace && whitespaceRangeStart < ch) {
			ch = input.skipWhitespace(ch, delimiter, quote);
		}

		while (ch != newLine) { // 没切行的时候.
			// 跳过记录级别的 whitespace
			if (ch <= ' ' && ignoreLeadingWhitespace && whitespaceRangeStart < ch) {
				ch = input.skipWhitespace(ch, delimiter, quote); // 遇到 delimiter 或者 quote 就停止.
			}

			if (ch == delimiter || ch == newLine) {
				// 读到一条空记录 或者直接是换行符, 就 emptyParsed.
				output.emptyParsed();
			} else {
				// 否则, 先设置 prev 和 unescaped 表示状态
				unescaped = false;
				prev = '\0';

				if (ch == quote) { // 开启 quote 模式
					// 手动吐出一些解析的 line, 内容肯定都会返回 newLine
					input.enableNormalizeLineEndings(normalizeLineEndingsInQuotes);
					int len = output.appender.length(); // 拿到最新的记录, 这个首先看看 record 有没有结尾. 顺便看看长度, -1 是说应该 skip.
					if (len == 0) { // 这是一条新记录
						// 读一个 quoted string
						String value = input.getQuotedString(quote, quoteEscape, escapeEscape, maxColumnLength, delimiter,
								newLine, /* 是否保留 quote, 默认肯定 false */ keepQuotes,  /* 是否保留 escape, 默认肯定 false */ keepEscape,
								trimQuotedLeading, trimQuotedTrailing);
						// TODO(maple): 什么时候会是 null?
						if (value != null) {
							// 看情况提交一个内容.
							// TODO(mwish): 这个 empty value 是什么情况
							output.valueParsed(value == "" ? emptyValue : value);
							// 设回来
							input.enableNormalizeLineEndings(true);
							// ch 不等于之前了, 需要重置 ch
							try {
								ch = input.nextChar();
								if (ch == delimiter) { // 如果是 delimiter, 那么这里提交了记录，需要切到下一个记录
									try {
										ch = input.nextChar(); // peek 下一个记录, 作为真正的 ch
										if (ch == newLine) { // delimiter 后面跟了 newLine, 那么 emit 一个 empty
											output.emptyParsed();
										}
									} catch (EOFException e) {
										output.emptyParsed(); // nextChar 是空的, 所以 emit 一个 empty
										return;
									}
								}
							} catch (EOFException e) {
								return;
							}
							continue;
						}
					} else if (len == -1 && input.skipQuotedString(quote, quoteEscape, delimiter, newLine)) {
						// Q: 为什么他妈的 skip 了?????
						// A: 如果这个字段需要被 skip, 返回的 length 就是 -1：
						//    见: https://github.com/uniVocity/univocity-parsers/commit/6746adc2ddb420ebba7441339887e4bbc35cf087
						output.valueParsed();
						try {
							ch = input.nextChar();
							if (ch == delimiter) {
								try {
									ch = input.nextChar();
									if (ch == newLine) {
										output.emptyParsed();
									}
								} catch (EOFException e) {
									output.emptyParsed();
									return;
								}
							}
						} catch (EOFException e) {
							return;
						}
						continue;
					}
					// 上面两个都是单行的处理, 当没处理完的时候, fallback 到 `parseQuotedValue()`, 来处理:
					// 1. 多行
					// 2. 需要额外 ref 的数据
					// 没 continue 就要处理跨行的 quote.
					output.trim = trimQuotedTrailing;
					parseQuotedValue(); // 完整解析 quotedValue.
					input.enableNormalizeLineEndings(true);
					// 满足要求的去走 ValueParsed
					// TODO(maple): 这个状态机我真逗不清楚了
					if (!(unescaped && quoteHandling == BACK_TO_DELIMITER && output.appender.length() == 0)) {
						output.valueParsed();
					}
				} else if (doNotEscapeUnquotedValues) {
					// doNotEscapeUnquotedValues 默认的配置, 对 unquote 的 a""b 之类的读的还是 a"b
					String value = null;
					int len = output.appender.length();
					if (len == 0) {
						// TODO(maple): getString 返回 null 是什么场景
						value = input.getString(ch, delimiter, ignoreTrailingWhitespace, nullValue, maxColumnLength);
					}
					if (value != null) {
						output.valueParsed(value);
						ch = input.getChar();
					} else {
						// 尝试 appendUntil, 然后读
						if (len != -1) {
							output.trim = ignoreTrailingWhitespace;
							ch = output.appender.appendUntil(ch, input, delimiter, newLine);
						} else {
							if (input.skipString(ch, delimiter)) {
								ch = input.getChar();
							} else {
								ch = output.appender.appendUntil(ch, input, delimiter, newLine);
							}
						}
						output.valueParsed();
					}
				} else {
					// escape unquoted values.
					output.trim = ignoreTrailingWhitespace;
					parseValueProcessingEscape();
					output.valueParsed();
				}
			}
			if (ch != newLine) {
				ch = input.nextChar();
				if (ch == newLine) {
					output.emptyParsed();
				}
			}
		}
	}

	private void skipValue() {
		// 把自己设置为 noop char appender.
		output.appender.reset();
		output.appender = NoopCharAppender.getInstance();
		if (multiDelimiter == null) {
			// 非 multi, 就读到尾部
			ch = NoopCharAppender.getInstance().appendUntil(ch, input, delimiter, newLine);
		} else {
			// TODO(maple): 怎么处理
			for (; match < multiDelimiter.length && ch != newLine; ch = input.nextChar()) {
				if (multiDelimiter[match] == ch) {
					match++;
				} else {
					match = 0;
				}
			}
		}
	}

	private void handleValueSkipping(boolean quoted) {
		switch (quoteHandling) {
			case SKIP_VALUE:
				skipValue();
				break;
			case RAISE_ERROR:
				throw new TextParsingException(context, "Unescaped quote character '" + quote
						+ "' inside " + (quoted ? "quoted" : "") + " value of CSV field. To allow unescaped quotes, set 'parseUnescapedQuotes' to 'true' in the CSV parser settings. Cannot parse CSV input.");
		}
	}

	private void handleUnescapedQuoteInValue() {
		switch (quoteHandling) {
			case BACK_TO_DELIMITER:
			case STOP_AT_CLOSING_QUOTE:
			case STOP_AT_DELIMITER:
				output.appender.append(quote);
				prev = ch;
				parseValueProcessingEscape();
				break;
			default:
				handleValueSkipping(false);
				break;
		}
	}

	private int nextDelimiter() {
		if (multiDelimiter == null) {
			return output.appender.indexOfAny(delimiters, 0);
		} else {
			int lineEnd = output.appender.indexOf(newLine, 0);
			int delimiter = output.appender.indexOf(multiDelimiter, 0);

			return lineEnd != -1 && lineEnd < delimiter ? lineEnd : delimiter;
		}
	}

	private boolean handleUnescapedQuote() {
		unescaped = true;
		switch (quoteHandling) {
			case BACK_TO_DELIMITER:
				int pos;
				int lastPos = 0;
				while ((pos = nextDelimiter()) != -1) {
					lastPos = pos;
					String value = output.appender.substring(0, pos);
					if (keepQuotes && output.appender.charAt(pos - 1) == quote) {
						value += quote;
					}
					output.valueParsed(value);
					if (output.appender.charAt(pos) == newLine) {
						output.pendingRecords.add(output.rowParsed());
						output.appender.remove(0, pos + 1);
						continue;
					}
					if (multiDelimiter == null) {
						output.appender.remove(0, pos + 1);
					} else {
						output.appender.remove(0, pos + multiDelimiter.length);
					}
				}
				if (keepQuotes && input.lastIndexOf(quote) > lastPos) {
					output.appender.append(quote);
				}
				output.appender.append(ch);
				prev = '\0';
				if (multiDelimiter == null) {
					parseQuotedValue();
				} else {
					parseQuotedValueMultiDelimiter();
				}
				return true;
			case STOP_AT_CLOSING_QUOTE:
			case STOP_AT_DELIMITER:
				// STOP_AT_DELIMITER 会给现有上下文加上 quote + ch, 当成正常文本.
				output.appender.append(quote);
				output.appender.append(ch);
				prev = ch; // prev 设置为现有的 prev, 表示有别的上下文.
				if (multiDelimiter == null) {
					// 递归的继续 parsing.
					parseQuotedValue();
				} else {
					parseQuotedValueMultiDelimiter();
				}
				return true; //continue;
			default:
				handleValueSkipping(true);
				return false;
		}
	}

	private void processQuoteEscape() {
		if (ch == quoteEscape && prev == escapeEscape && escapeEscape != '\0') {
			if (keepEscape) {
				output.appender.append(escapeEscape);
			}
			output.appender.append(quoteEscape);
			ch = '\0';
		} else if (prev == quoteEscape) {
			if (ch == quote) {
				if (keepEscape) {
					output.appender.append(quoteEscape);
				}
				output.appender.append(quote);
				ch = '\0';
			} else {
				output.appender.append(prev);
			}
		} else if (ch == quote && prev == quote) {
			output.appender.append(quote);
		} else if (prev == quote) { //unescaped quote detected
			handleUnescapedQuoteInValue();
		}
	}

	private void parseValueProcessingEscape() {
		// 没有处理到 delimiter 和 newLine 的时候
		while (ch != delimiter && ch != newLine) {
			if (ch != quote && ch != quoteEscape) {
				if (prev == quote) { //unescaped quote detected
					handleUnescapedQuoteInValue();
					return;
				}
				output.appender.append(ch);
			} else {
				processQuoteEscape();
			}
			prev = ch;
			ch = input.nextChar();
		}
	}

	private void parseQuotedValue() {
		// TODO(maple): prev != \0 是什么意思
		// until delimiter: https://github.com/uniVocity/univocity-parsers/commit/72519a11de81f388cb528542170795dc02d4d9d6
		if (prev != '\0' && parseUnescapedQuotesUntilDelimiter) {
			// 如果是 skipValue, 需要跳转到 ',', 来跳过这条记录.
			if (quoteHandling == SKIP_VALUE) {
				skipValue();
				return;
			}
			// 如果是 stopAtDelimiter, 那么需要读到结束
			if (!keepQuotes) {
				output.appender.prepend(quote);
			}
			ch = input.nextChar();
			output.trim = ignoreTrailingWhitespace;
			// 往前尽量读
			ch = output.appender.appendUntil(ch, input, delimiter, newLine);
		} else {
			// 如果要 keepQuotes, 就需要保留 quote.
			if (keepQuotes && prev == '\0') {
				output.appender.append(quote);
			}
			ch = input.nextChar();

			// trim 掉内部前面的
			if (trimQuotedLeading && ch <= ' ' && output.appender.length() == 0) {
				while ((ch = input.nextChar()) <= ' ') ;
			}

			while (true) {
				// quote + ' ' / delimiter / newLine, 说明解析完了
				// <= 是因为: https://github.com/uniVocity/univocity-parsers/issues/115 , 这里希望解析看不到的.
				if (prev == quote && (ch <= ' ' && whitespaceRangeStart < ch || ch == delimiter || ch == newLine)) {
					break;
				}

				// 算法: 往前推进, 如果碰到 quote / quoteEscape, 那么特殊处理, 否则一直推进
				// quote + quoteEscape -> quote
				//
				if (ch != quote && ch != quoteEscape) {
					if (prev == quote) { //unescaped quote detected
						// 返回 true 代表能够继续解析.
						if (handleUnescapedQuote()) {
							// TODO(maple): 这个 if else 我没搞懂.
							if (quoteHandling == SKIP_VALUE) {
								break;
							} else {
								return;
							}
						} else {
							return;
						}
					}
					// 单个 quoteEscape 会被处理成 quoteEscape 本身, 不需要转义.
					if (prev == quoteEscape && quoteEscape != '\0') {
						output.appender.append(quoteEscape);
					}
					ch = output.appender.appendUntil(ch, input, quote, quoteEscape, escapeEscape);
					prev = ch;
					ch = input.nextChar();
				} else {
					// ch == quote || ch == quoteEscape
					// 处理 quote escape
					processQuoteEscape();
					prev = ch;
					ch = input.nextChar();
					if (unescaped && (ch == delimiter || ch == newLine)) {
						return;
					}
				}
			}

			// handles whitespaces after quoted value: whitespaces are ignored. Content after whitespaces may be parsed if 'parseUnescapedQuotes' is enabled.
			//
			// 处理(可能) 有的 whiteSpace.
			if (ch != delimiter && ch != newLine && ch <= ' ' && whitespaceRangeStart < ch) {
				whitespaceAppender.reset();
				do {
					//saves whitespaces after value
					whitespaceAppender.append(ch);
					ch = input.nextChar();
					//found a new line, go to next record.
					if (ch == newLine) {
						if (keepQuotes) {
							output.appender.append(quote);
						}
						return;
					}
				} while (ch <= ' ' && whitespaceRangeStart < ch && ch != delimiter);

				//there's more stuff after the quoted value, not only empty spaces.
				if (ch != delimiter && parseUnescapedQuotes) {
					if (output.appender instanceof DefaultCharAppender) {
						//puts the quote before whitespaces back, then restores the whitespaces
						output.appender.append(quote);
						((DefaultCharAppender) output.appender).append(whitespaceAppender);
					}
					//the next character is not the escape character, put it there
					if (parseUnescapedQuotesUntilDelimiter || ch != quote && ch != quoteEscape) {
						output.appender.append(ch);
					}

					//sets this character as the previous character (may be escaping)
					//calls recursively to keep parsing potentially quoted content
					prev = ch;
					parseQuotedValue();
				} else if (keepQuotes) {
					output.appender.append(quote);
				}
			} else if (keepQuotes) {
				output.appender.append(quote);
			}

			if (ch != delimiter && ch != newLine) {
				throw new TextParsingException(context, "Unexpected character '" + ch + "' following quoted value of CSV field. Expecting '" + delimiter + "'. Cannot parse CSV input.");
			}
		}
	}

	@Override
	protected final InputAnalysisProcess getInputAnalysisProcess() {
		if (settings.isDelimiterDetectionEnabled() || settings.isQuoteDetectionEnabled()) {
			return new CsvFormatDetector(formatDetectorRowSampleCount, settings, whitespaceRangeStart) {
				@Override
				protected void apply(char delimiter, char quote, char quoteEscape) {
					if (settings.isDelimiterDetectionEnabled()) {
						CsvParser.this.delimiter = delimiter;
						CsvParser.this.delimiters[0] = delimiter;

					}
					if (settings.isQuoteDetectionEnabled()) {
						CsvParser.this.quote = quote;
						CsvParser.this.quoteEscape = quoteEscape;
					}
				}
			};
		}
		return null;
	}

	/**
	 * Returns the CSV format detected when one of the following settings is enabled:
	 * <ul>
	 * <li>{@link CommonParserSettings#isLineSeparatorDetectionEnabled()}</li>
	 * <li>{@link CsvParserSettings#isDelimiterDetectionEnabled()}</li>
	 * <li>{@link CsvParserSettings#isQuoteDetectionEnabled()}</li>
	 * </ul>
	 *
	 * The detected format will be available once the parsing process is initialized (i.e. when {@link AbstractParser#beginParsing(Reader) runs}.
	 *
	 * @return the detected CSV format, or {@code null} if no detection has been enabled or if the parsing process has not been started yet.
	 */
	public final CsvFormat getDetectedFormat() {
		CsvFormat out = null;
		if (settings.isDelimiterDetectionEnabled()) {
			out = settings.getFormat().clone();
			out.setDelimiter(this.delimiter);
		}
		if (settings.isQuoteDetectionEnabled()) {
			out = out == null ? settings.getFormat().clone() : out;
			out.setQuote(quote);
			out.setQuoteEscape(quoteEscape);
		}
		if (settings.isLineSeparatorDetectionEnabled()) {
			out = out == null ? settings.getFormat().clone() : out;
			out.setLineSeparator(input.getLineSeparator());
		}
		return out;
	}

	@Override
	protected final boolean consumeValueOnEOF() {
		if (ch == quote) {
			if (prev == quote) {
				if (keepQuotes) {
					output.appender.append(quote);
				}
				return true;
			} else {
				if (!unescaped) {
					output.appender.append(quote);
				}
			}
		}
		boolean out = prev != '\0' && ch != delimiter && ch != newLine && ch != comment;
		ch = prev = '\0';
		if (match > 0) {
			saveMatchingCharacters();
			return true;
		}
		return out;
	}

	/**
	 * Allows changing the format of the input on the fly.
	 *
	 * @param format the new format to use.
	 */
	public final void updateFormat(CsvFormat format) {
		newLine = format.getNormalizedNewline();
		multiDelimiter = format.getDelimiterString().toCharArray();
		if (multiDelimiter.length == 1) {
			multiDelimiter = null;
			delimiter = format.getDelimiter();
			delimiters = new char[]{delimiter, newLine};
		} else {
			delimiters = new char[]{multiDelimiter[0], newLine};
		}
		quote = format.getQuote();
		quoteEscape = format.getQuoteEscape();
		escapeEscape = format.getCharToEscapeQuoteEscaping();
	}

	private void skipWhitespace() {
		while (ch <= ' ' && match < multiDelimiter.length && ch != newLine && ch != quote && whitespaceRangeStart < ch) {
			ch = input.nextChar();
			if (multiDelimiter[match] == ch) {
				if (matchDelimiter()) {
					output.emptyParsed();
					ch = input.nextChar();
				}
			}
		}

		saveMatchingCharacters();
	}

	private void saveMatchingCharacters() {
		if (match > 0) {
			if (match < multiDelimiter.length) {
				output.appender.append(multiDelimiter, 0, match);
			}
			match = 0;
		}
	}

	private boolean matchDelimiter() {
		while (ch == multiDelimiter[match]) {
			match++;
			if (match == multiDelimiter.length) {
				break;
			}
			ch = input.nextChar();
		}

		if (multiDelimiter.length == match) {
			match = 0;
			return true;
		}

		if (match > 0) {
			saveMatchingCharacters();
		}

		return false;
	}

	private boolean matchDelimiterAfterQuote() {
		while (ch == multiDelimiter[match]) {
			match++;
			if (match == multiDelimiter.length) {
				break;
			}
			ch = input.nextChar();
		}

		if (multiDelimiter.length == match) {
			match = 0;
			return true;
		}

		return false;
	}

	private void parseMultiDelimiterRecord() {
		if (ch <= ' ' && ignoreLeadingWhitespace && whitespaceRangeStart < ch) {
			skipWhitespace();
		}

		while (ch != newLine) {
			if (ch <= ' ' && ignoreLeadingWhitespace && whitespaceRangeStart < ch) {
				skipWhitespace();
			}

			if (ch == newLine || matchDelimiter()) {
				output.emptyParsed();
			} else {
				unescaped = false;
				prev = '\0';
				if (ch == quote && output.appender.length() == 0) {
					input.enableNormalizeLineEndings(normalizeLineEndingsInQuotes);
					output.trim = trimQuotedTrailing;
					parseQuotedValueMultiDelimiter();
					input.enableNormalizeLineEndings(true);
					if (!(unescaped && quoteHandling == BACK_TO_DELIMITER && output.appender.length() == 0)) {
						output.valueParsed();
					}
				} else if (doNotEscapeUnquotedValues) {
					appendUntilMultiDelimiter();
					if (ignoreTrailingWhitespace) {
						output.appender.updateWhitespace();
					}
					output.valueParsed();
				} else {
					output.trim = ignoreTrailingWhitespace;
					parseValueProcessingEscapeMultiDelimiter();
					output.valueParsed();
				}
			}
			if (ch != newLine) {
				ch = input.nextChar();
				if (ch == newLine) {
					output.emptyParsed();
				}
			}
		}
	}

	private void appendUntilMultiDelimiter() {
		while (match < multiDelimiter.length && ch != newLine) {
			if (multiDelimiter[match] == ch) {
				match++;
				if (match == multiDelimiter.length) {
					break;
				}
			} else {
				if (match > 0) {
					saveMatchingCharacters();
					continue;
				}
				output.appender.append(ch);
			}
			ch = input.nextChar();
		}
		saveMatchingCharacters();
	}

	private void parseQuotedValueMultiDelimiter() {
		if (prev != '\0' && parseUnescapedQuotesUntilDelimiter) {
			if (quoteHandling == SKIP_VALUE) {
				skipValue();
				return;
			}
			if (!keepQuotes) {
				output.appender.prepend(quote);
			}
			ch = input.nextChar();
			output.trim = ignoreTrailingWhitespace;
			appendUntilMultiDelimiter();
		} else {
			if (keepQuotes && prev == '\0') {
				output.appender.append(quote);
			}
			ch = input.nextChar();

			if (trimQuotedLeading && ch <= ' ' && output.appender.length() == 0) {
				while ((ch = input.nextChar()) <= ' ') ;
			}

			while (true) {
				// 遇到 newLine 也 break, `",` 之类的, 处理完了
				if (prev == quote && (ch <= ' ' && whitespaceRangeStart < ch || ch == newLine)) {
					break;
				}
				// quote + multi delimiter: https://github.com/uniVocity/univocity-parsers/issues/404
				if (prev == quote && matchDelimiter()) {
					if (keepQuotes) {
						output.appender.append(quote);
					}
					return;
				}

				if (ch != quote && ch != quoteEscape) {
					if (prev == quote) { //unescaped quote detected
						if (handleUnescapedQuote()) {
							if (quoteHandling == SKIP_VALUE) {
								break;
							} else {
								return;
							}
						} else {
							return;
						}
					}
					// 单个 quoteEscape 不要紧的, 这里主要是 quoteEscape != quote 的情况.
					if (prev == quoteEscape && quoteEscape != '\0') {
						output.appender.append(quoteEscape);
					}
					// 往后傻插, 直到遇到 quote / quoteEscape / escapeEscape
					// (那换行符呢...不重要, 这里其实是 ignore 换行符的...)
					ch = output.appender.appendUntil(ch, input, quote, quoteEscape, escapeEscape);
					prev = ch;
					ch = input.nextChar();
				} else {
					processQuoteEscape();
					prev = ch;
					ch = input.nextChar();
					if (unescaped && (ch == newLine || matchDelimiter())) {
						return;
					}
				}
			}
		}

		// handles whitespaces after quoted value: whitespaces are ignored. Content after whitespaces may be parsed if 'parseUnescapedQuotes' is enabled.
		if (ch != newLine && ch <= ' ' && whitespaceRangeStart < ch && !matchDelimiterAfterQuote()) {
			whitespaceAppender.reset();
			do {
				//saves whitespaces after value
				whitespaceAppender.append(ch);
				ch = input.nextChar();
				//found a new line, go to next record.
				if (ch == newLine) {
					if (keepQuotes) {
						output.appender.append(quote);
					}
					return;
				}
				if (matchDelimiterAfterQuote()) {
					return;
				}
			} while (ch <= ' ' && whitespaceRangeStart < ch);

			//there's more stuff after the quoted value, not only empty spaces.
			if (parseUnescapedQuotes && !matchDelimiterAfterQuote()) {
				if (output.appender instanceof DefaultCharAppender) {
					//puts the quote before whitespaces back, then restores the whitespaces
					output.appender.append(quote);
					((DefaultCharAppender) output.appender).append(whitespaceAppender);
				}
				//the next character is not the escape character, put it there
				if (parseUnescapedQuotesUntilDelimiter || ch != quote && ch != quoteEscape) {
					output.appender.append(ch);
				}

				//sets this character as the previous character (may be escaping)
				//calls recursively to keep parsing potentially quoted content
				prev = ch;
				parseQuotedValue();
			} else if (keepQuotes) {
				output.appender.append(quote);
			}
		} else if (keepQuotes && (!unescaped || quoteHandling == STOP_AT_CLOSING_QUOTE)) {
			output.appender.append(quote);
		}

	}

	private void parseValueProcessingEscapeMultiDelimiter() {
		while (ch != newLine && !matchDelimiter()) {
			if (ch != quote && ch != quoteEscape) {
				if (prev == quote) { //unescaped quote detected
					handleUnescapedQuoteInValue();
					return;
				}
				output.appender.append(ch);
			} else {
				processQuoteEscape();
			}
			prev = ch;
			ch = input.nextChar();
		}
	}
}
