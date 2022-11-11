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
package com.univocity.parsers.common.input;

import com.univocity.parsers.common.*;

import java.io.*;
import java.util.*;

/**
 * The base class for implementing different flavours of {@link CharInputReader}.
 *
 * <p> It provides the essential conversion of sequences of newline characters defined by {@link Format#getLineSeparator()} into the normalized newline character provided in {@link Format#getNormalizedNewline()}.
 * <p> It also provides a default implementation for most of the methods specified by the {@link CharInputReader} interface.
 * <p> Extending classes must essentially read characters from a given {@link java.io.Reader} and assign it to the public {@link AbstractCharInputReader#buffer} when requested (in the {@link AbstractCharInputReader#reloadBuffer()} method).
 *
 * @author Univocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 * @see com.univocity.parsers.common.Format
 * @see com.univocity.parsers.common.input.DefaultCharInputReader
 * @see com.univocity.parsers.common.input.concurrent.ConcurrentCharInputReader
 */

public abstract class AbstractCharInputReader implements CharInputReader {

	private final ExpandingCharAppender tmp;
	private boolean lineSeparatorDetected;
	private final boolean detectLineSeparator;
	private List<InputAnalysisProcess> inputAnalysisProcesses;
	private char lineSeparator1;
	private char lineSeparator2;
	private final char normalizedLineSeparator;

	private long lineCount;
	private long charCount;
	private int recordStart;
	final int whitespaceRangeStart;
	private boolean skipping = false;
	private boolean commentProcessing = false;
	protected final boolean closeOnStop;

	/**
	 * Current position in the buffer
	 */
	public int i;

	private char ch;

	// TODO(maple): 这段怎么搞.

	/**
	 * The buffer itself
	 */
	public char[] buffer;

	/**
	 * Number of characters available in the buffer.
	 */
	public int length = -1;
	private boolean incrementLineCount;
	private boolean normalizeLineEndings = true;

	/**
	 * Creates a new instance that attempts to detect the newlines used in the input automatically.
	 *
	 * @param normalizedLineSeparator the normalized newline character (as defined in {@link Format#getNormalizedNewline()}) that is used to replace any lineSeparator sequence found in the input.
	 * @param whitespaceRangeStart    starting range of characters considered to be whitespace.
	 * @param closeOnStop			  indicates whether to automatically close the input when {@link #stop()} is called
	 */
	public AbstractCharInputReader(char normalizedLineSeparator, int whitespaceRangeStart, boolean closeOnStop) {
		this(null, normalizedLineSeparator, whitespaceRangeStart, closeOnStop);
	}

	/**
	 * Creates a new instance with the mandatory characters for handling newlines transparently.
	 *
	 * @param lineSeparator           the sequence of characters that represent a newline, as defined in {@link Format#getLineSeparator()}
	 * @param normalizedLineSeparator the normalized newline character (as defined in {@link Format#getNormalizedNewline()}) that is used to replace any lineSeparator sequence found in the input.
	 * @param whitespaceRangeStart    starting range of characters considered to be whitespace.
	 * @param closeOnStop			  indicates whether to automatically close the input when {@link #stop()} is called
	 */
	public AbstractCharInputReader(char[] lineSeparator, char normalizedLineSeparator, int whitespaceRangeStart, boolean closeOnStop) {
		this.whitespaceRangeStart = whitespaceRangeStart;
		this.tmp = new ExpandingCharAppender(4096, null, whitespaceRangeStart);
		if (lineSeparator == null) {
			detectLineSeparator = true;
			submitLineSeparatorDetector();
			this.lineSeparator1 = '\0';
			this.lineSeparator2 = '\0';
		} else {
			setLineSeparator(lineSeparator);
			this.detectLineSeparator = false;
		}

		this.normalizedLineSeparator = normalizedLineSeparator;
		this.closeOnStop = closeOnStop;
	}


	private void submitLineSeparatorDetector() {
		if (detectLineSeparator && !lineSeparatorDetected) {
			addInputAnalysisProcess(new LineSeparatorDetector() {
				@Override
				protected void apply(char separator1, char separator2) {
					if (separator1 != '\0') {
						lineSeparatorDetected = true;
						lineSeparator1 = separator1;
						lineSeparator2 = separator2;
					} else {
						setLineSeparator(Format.getSystemLineSeparator());
					}
				}
			});
		}
	}

	private void setLineSeparator(char[] lineSeparator) {
		if (lineSeparator == null || lineSeparator.length == 0) {
			throw new IllegalArgumentException("Invalid line separator. Expected 1 to 2 characters");
		}
		if (lineSeparator.length > 2) {
			throw new IllegalArgumentException("Invalid line separator. Up to 2 characters are expected. Got " + lineSeparator.length + " characters.");
		}
		this.lineSeparator1 = lineSeparator[0];
		this.lineSeparator2 = lineSeparator.length == 2 ? lineSeparator[1] : '\0';
	}

	/**
	 * Passes the {@link java.io.Reader} provided in the {@link AbstractCharInputReader#start(Reader)} method to the extending class so it can begin loading characters from it.
	 *
	 * @param reader the {@link java.io.Reader} provided in {@link AbstractCharInputReader#start(Reader)}
	 */
	protected abstract void setReader(Reader reader);

	/**
	 * Informs the extending class that the buffer has been read entirely and requests for another batch of characters.
	 * Implementors must assign the new character buffer to the public {@link AbstractCharInputReader#buffer} attribute, as well as the number of characters available to the public {@link AbstractCharInputReader#length} attribute.
	 * To notify the input does not have any more characters, {@link AbstractCharInputReader#length} must receive the <b>-1</b> value
	 */
	protected abstract void reloadBuffer();

	protected final void unwrapInputStream(BomInput.BytesProcessedNotification notification) {
		InputStream inputStream = notification.input;
		String encoding = notification.encoding;

		if (encoding != null) {
			try {
				start(new InputStreamReader(inputStream, encoding));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		} else {
			length = -1;
			start(new InputStreamReader(inputStream), false);
		}
	}

	private void start(Reader reader, boolean resetTmp){
		if(resetTmp) {
			tmp.reset();
		}
		stop();
		setReader(reader);
		lineCount = 0;

		lineSeparatorDetected = false;
		submitLineSeparatorDetector();

		updateBuffer();

		//if the input has been properly decoded with the correct UTF* character set, but has a BOM marker, we can safely discard it.
		if (length > 0 && buffer[0] == '\uFEFF') { //regardless of the UTF* encoding used, the BOM bytes always produce the '\uFEFF' character when decoded.
			i++;
		}
	}

	@Override
	public final void start(Reader reader) {
		start(reader, true);
	}

	/**
	 * Requests the next batch of characters from the implementing class and updates
	 * the character count.
	 *
	 * <p> If there are no more characters in the input, the reading will stop by invoking the {@link AbstractCharInputReader#stop()} method.
	 */
	private void updateBuffer() {
		if (!commentProcessing && length - recordStart > 0 && buffer != null && !skipping) {
			tmp.append(buffer, recordStart, length - recordStart);
		}
		recordStart = 0;
		reloadBuffer();

		charCount += i;
		i = 0;

		if (length == -1) {
			stop();
			incrementLineCount = true;
		}

		if (inputAnalysisProcesses != null) {
			if (length > 0 && length <= 4) {
				int tmpLength = length;
				char[] tmp = Arrays.copyOfRange(buffer, 0, length + 1); // length + 1 to assist CSV detection process: length < buffer.length indicates all data was read into the buffer.

				//sets processes temporarily to null to prevent them running if method `unwrapInputStream` is called.
				List<InputAnalysisProcess> processes = inputAnalysisProcesses;
				inputAnalysisProcesses = null;
				reloadBuffer();
				inputAnalysisProcesses = processes;

				if (length != -1) {
					char[] newBuffer = new char[tmpLength + buffer.length];
					System.arraycopy(tmp, 0, newBuffer, 0, tmpLength);
					System.arraycopy(buffer, 0, newBuffer, tmpLength, length);
					buffer = newBuffer;
					length += tmpLength;
				} else {
					buffer = tmp;
					length = tmpLength;
				}
			}
			try {
				for (InputAnalysisProcess process : inputAnalysisProcesses) {
					process.execute(buffer, length);
				}
			} finally {
				if (length > 4) {
					inputAnalysisProcesses = null;
				}
			}
		}
	}

	/**
	 * Submits a custom {@link InputAnalysisProcess} to analyze the input buffer and potentially discover configuration options such as
	 * column separators is CSV, data formats, etc. The process will be execute only once.
	 *
	 * @param inputAnalysisProcess a custom process to analyze the contents of the input buffer.
	 */
	public final void addInputAnalysisProcess(InputAnalysisProcess inputAnalysisProcess) {
		if (inputAnalysisProcess == null) {
			return;
		}
		if (this.inputAnalysisProcesses == null) {
			inputAnalysisProcesses = new ArrayList<InputAnalysisProcess>();
		}
		inputAnalysisProcesses.add(inputAnalysisProcess);
	}

	private void throwEOFException() {
		if (incrementLineCount) {
			lineCount++;
		}
		ch = '\0';
		throw new EOFException();
	}

	@Override
	public final char nextChar() {
		if (length == -1) {
			throwEOFException();
		}

		ch = buffer[i++];

		// 切换到下一个 buffer, 尽量让之后都有
		if (i >= length) {
			updateBuffer();
		}

		if (lineSeparator1 == ch && (lineSeparator2 == '\0' || length != -1 && lineSeparator2 == buffer[i])) {
			lineCount++;
			if (normalizeLineEndings) {
				ch = normalizedLineSeparator; // 替换成 normalized
				if (lineSeparator2 == '\0') { // 如果 lineSep 只有一个字符, 那就返回 `normalized`
					return ch;
				}
				if (++i >= length) { // 跳过第二个字符.
					if (length != -1) {
						updateBuffer();
					} else {
						throwEOFException();
					}
				}
			}
		}

		return ch;
	}

	@Override
	public final char getChar() {
		return ch;
	}

	@Override
	public final long lineCount() {
		return lineCount;
	}


	@Override
	public final void skipLines(long lines) {
		if (lines < 1) {
			skipping = false;
			return;
		}
		skipping = true;
		long expectedLineCount = this.lineCount + lines;

		try {
			do {
				nextChar();
			} while (lineCount < expectedLineCount);
			skipping = false;
		} catch (EOFException ex) {
			skipping = false;
		}
	}

	@Override
	public String readComment() {
		long expectedLineCount = lineCount + 1;
		commentProcessing = true;
		tmp.reset();
		try {
			do {
				char ch = nextChar();
				if (ch <= ' ' && whitespaceRangeStart < ch) {
					ch = skipWhitespace(ch, normalizedLineSeparator, normalizedLineSeparator);
				}
				tmp.appendUntil(ch, this, normalizedLineSeparator, normalizedLineSeparator);

				if (lineCount < expectedLineCount) {
					tmp.appendIgnoringWhitespace(nextChar());
				} else {
					tmp.updateWhitespace();
					return tmp.getAndReset();
				}
			} while (true);
		} catch (EOFException ex) {
			tmp.updateWhitespace();
			return tmp.getAndReset();
		} finally {
			commentProcessing = false;
		}
	}

	@Override
	public final long charCount() {
		return charCount + i;
	}

	@Override
	public final void enableNormalizeLineEndings(boolean normalizeLineEndings) {
		this.normalizeLineEndings = normalizeLineEndings;
	}

	@Override
	public char[] getLineSeparator() {
		if (lineSeparator2 != '\0') {
			return new char[]{lineSeparator1, lineSeparator2};
		} else {
			return new char[]{lineSeparator1};
		}
	}

	@Override
	public final char skipWhitespace(char ch, char stopChar1, char stopChar2) {
		while (ch <= ' ' && ch != stopChar1 && ch != normalizedLineSeparator && ch != stopChar2 && whitespaceRangeStart < ch) {
			ch = nextChar();
		}
		return ch;
	}

	@Override
	public final int currentParsedContentLength() {
		return i - recordStart + tmp.length();
	}

	@Override
	public final String currentParsedContent() {
		if (tmp.length() == 0) {
			if (i > recordStart) {
				return new String(buffer, recordStart, i - recordStart);
			}
			return null;
		}
		if (i > recordStart) {
			tmp.append(buffer, recordStart, i - recordStart);
		}
		return tmp.getAndReset();
	}

	@Override
	public final int lastIndexOf(char ch) {
		if (tmp.length() == 0) {
			if (i > recordStart) {
				for (int x = i - 1, c = 0; x >= recordStart; x--, c++) {
					if (buffer[x] == ch) {
						return recordStart + c;
					}
				}
			}
			return -1;
		}
		if(i > recordStart){
			for (int x = i - 1, c = 0; x >= recordStart; x--, c++) {
				if (buffer[x] == ch) {
					return tmp.length() + recordStart + c;
				}
			}
		}
		return tmp.lastIndexOf(ch);
	}

	@Override
	public final void markRecordStart() {
		tmp.reset();
		recordStart = i % length;
	}

	@Override
	public final boolean skipString(char ch, char stop) {
		if (i == 0) {
			return false;
		}
		int i = this.i;
		for (; ch != stop; ch = buffer[i++]) {
			if (i >= length) {
				return false;
			}
			if (lineSeparator1 == ch && (lineSeparator2 == '\0' || lineSeparator2 == buffer[i])) {
				break;
			}
		}

		this.i = i - 1;

		nextChar();

		return true;
	}

	@Override
	public final String getString(char ch, char stop, boolean trim, String nullValue, int maxLength) {
		if (i == 0) {
			return null;
		}
		int i = this.i;
		for (; ch != stop; ch = buffer[i++]) {
			if (i >= length) {
				return null;
			}
			if (lineSeparator1 == ch && (lineSeparator2 == '\0' || lineSeparator2 == buffer[i])) {
				break;
			}
		}

		int pos = this.i - 1;
		int len = i - this.i;
		if (maxLength != -1 && len > maxLength) { //validating before trailing whitespace handling so this behaves as an appender.
			return null;
		}

		this.i = i - 1;

		if (trim) {
			i = i - 2;
			while (i >= 0 && buffer[i] <= ' ' && whitespaceRangeStart < buffer[i]) {
				len--;
				i--;
			}
		}

		String out;
		if (len <= 0) {
			out = nullValue;
		} else {
			out = new String(buffer, pos, len);
		}

		nextChar();

		return out;
	}

	@Override
	public final String getQuotedString(char quote, char escape, char escapeEscape, int maxLength, char stop1, char stop2, boolean keepQuotes, boolean keepEscape, boolean trimLeading, boolean trimTrailing) {
		if (i == 0) { // 什么垃圾我操, 大概是必须尝试 eat 掉一个 quote token.
			return null;
		}

		int i = this.i;

		while (true) {
			// 超过了, 走
			if (i >= length) {
				return null;
			}
			ch = buffer[i];
			if (ch == quote) {
				// 如果上一个是 escape, 那么不做任何处理, 需要从字符串上拷贝一段.
				if (buffer[i - 1] == escape) {
					// KeepEscape 的话, 就不需要 break, 否则需要拷贝原来的字符串.
					if (keepEscape) {
						i++;
						continue;
					}
					return null;
				}
				// 前瞻一个字符, 查看是不是一个 ending quote.
				if (i + 1 < length) {
					char next = buffer[i + 1];
					if (next == stop1 || next == stop2) {
						break;
					}
				}

				return null;
			} else if (ch == escape && !keepEscape) {
				// 遇到 escape, 如果不 keep escape, 那么
				// 尝试前瞻一个字符, 如果 escape 起到了作用, 即下一个是 quote, 那么滚了
				// TODO(maple): 这个地方 escape + 别的字符实际上处于没处理状态吧...
				if (i + 1 < length) {
					char next = buffer[i + 1];
					if (next == quote || next == escapeEscape) {
						return null;
					}
				}
			} else if (lineSeparator1 == ch && normalizeLineEndings && (lineSeparator2 == '\0' || i + 1 < length && lineSeparator2 == buffer[i + 1])) {
				// 在遇到有效 quote 之前就换行了
				return null;
			}
			i++;
		}

		// ref 一份 buffer 然后返回.
		int pos = this.i;
		int len = i - this.i;
		if (maxLength != -1 && len > maxLength) { //validating before trailing whitespace handling so this behaves as an appender.
			return null;
		}

		if (keepQuotes) {
			pos--;
			len += 2;
		} else {
			if (trimTrailing) {
				while (len > 0 && buffer[pos + len - 1] <= ' ') {
					len--;
				}
			}
			if (trimLeading) {
				while (len > 0 && buffer[pos] <= ' ') {
					pos++;
					len--;
				}
			}
		}

		this.i = i + 1;

		String out;
		if (len <= 0) {
			out = "";
		} else {
			out = new String(buffer, pos, len);
		}

		if (this.i >= length) {
			updateBuffer();
		}
		return out;
	}

	// 这个函数不能处理:
	// 1. 没消费完 buffer
	// 2. 遇到了换行符
	// 两种场景. 2 可以理解, 1 我都不太理解为什么
	public final boolean skipQuotedString(char quote, char escape, char stop1, char stop2) {
		if (i == 0) {
			return false;
		}

		int i = this.i; // 设置一个现有的位置, 因为没有遇到 stop 的话, 不应该消费 contents.

		while (true) {
			if (i >= length) { // 已经读完了所有的内容, 返回 false, 不做消费.
				return false;
			}
			// 现在处理的 buf
			ch = buffer[i];
			// 如果自己是 quote, 那么 prev 看看上一个是不是 escape
			// 因为第一个左 quote 不会包含在内。
			if (ch == quote) {
				// 遇到了 "", 跳出.
				if (buffer[i - 1] == escape) {
					i++;
					continue;
				}
				if (i + 1 < length) {
					// 前瞻一个字符, 等于 stop1(,) 或者 stop2(换行) 即脱出.
					// 这里是说明, 如果匹配到了 `",` 或者 `"\n`, 需要 break.
					char next = buffer[i + 1];
					if (next == stop1 || next == stop2) {
						break;
					}
				}

				// notice: 如果是 `" ,`, 这种下一个不是 stop 的, 就会 return false, 不会实际消费内容
				return false;
			} else if (lineSeparator1 == ch && normalizeLineEndings && (lineSeparator2 == '\0' || i + 1 < length && lineSeparator2 == buffer[i + 1])) {
				// 不等于 quote 的时候, 如果遇到了换行, 就会 break
				// 我不知道为啥会这样, 可能得看看 multiline 怎么处理的
				return false;
			}
			i++;
		}

		// step forward.
		// 如果是 break
		this.i = i + 1;

		// i 在范围内, i + 1 超过了 length, 这个地方返回 false.
		if (this.i >= length) {
			updateBuffer();
		}
		return true;
	}
}
