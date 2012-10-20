package neb.util.regex;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deconstructs regular expressions, as understood by the
 * <code>java.util.regex.Pattern</code> class, into the strings that will match
 * them.
 * <p>
 * An instance of <tt>PatternMatchGatherer</tt> is created per regular
 * expression to be deconstructed. Upon creation of a new
 * <tt>PatternMatchGatherer</tt>, a <tt>PatternSyntaxException</tt> may be
 * thrown if the regular expression's syntax is not understood by the
 * <code>java.util.regex.Pattern</code> class; otherwise, the expression is
 * reparsed (and the <tt>PatternMatchGatherer</tt> instance being created may
 * throw <tt>UnsupportedOperationException</tt> if the expression is too
 * complex) into an internal representation.
 * <p>
 * After the expression is reparsed in this manner, an instance is considered
 * ready for use. The Strings that match the regular expression are returned via
 * a <tt>List</tt>, unless the regular expression matches an <i>infinite set</i>
 * of Strings &mdash in which case an <tt>InfiniteMatchException</tt> will be
 * thrown &mdash;, or is an <i>impossible expression</i> &mdash; in which case
 * the <tt>List</tt> will be empty.
 * <p>
 * A regular expression matches an <i>infinite set</i> of Strings if either of
 * the following is true for it:
 * <ul>
 * <li>Any alternative inside the regular expression is not anchored to the
 * start of the string (e.g. <tt>blah$</tt>, <tt>blah</tt>);
 * <li>Any alternative inside the regular expression is not anchored to the end
 * of the string (e.g. <tt>^blah</tt>, <tt>blah</tt>);
 * <li>Any element inside the regular expression is repeated one or more times
 * with the <tt>+</tt> metacharacter (e.g. <tt>^bla+h$</tt>);
 * <li>Any element inside the regular expression is repeated zero or more times
 * with the <tt>*</tt> metacharacter (e.g. <tt>^bla*h$</tt>);
 * <li>Any element inside the regular expression is repeated a number of times
 * that has no upper bound with the <tt>{}</tt> metacharacters (e.g.
 * <tt>^bla{5,}h$</tt>).
 * </ul>
 * A regular expression is <i>impossible</i> if either of the following is true
 * for it:
 * <ul>
 * <li>The <tt>^</tt> metacharacter is found more than once in all of the
 * regular expression's alternatives, and, in each case, at least one of the
 * occurrences of <tt>^</tt> following the first also follows at least one
 * matched character &mdash; in other words, in all alternatives, there is at
 * least one <tt>^</tt> metacharacter that cannot logically match the start of
 * the string (e.g. <tt>^(b^lah|hal^b)$</tt> is the two alternatives
 * <tt>^b^lah$</tt> and <tt>^hal^b$</tt>);
 * <li>The <tt>$</tt> metacharacter is found more than once in all of the
 * regular expression's alternatives, and, in each case, at least one of the
 * occurrences of <tt>$</tt> preceding the last also precedes at least one
 * matched character &mdash; in other words, in all alternatives, there is at
 * least one <tt>$</tt> metacharacter that cannot logically match the end of the
 * string (e.g. <tt>^(b$lah|hal$b)$</tt> is the two alternatives
 * <tt>^b$lah$</tt> and <tt>^hal$b$</tt>).
 * </ul>
 * Code example: <blockquote>
 * 
 * <pre>
 * import neb.util.regex.*;
 * import java.util.*;
 * 
 * PatternMatchGatherer d = new PatternMatchGatherer("^b([oe])\\1p$");
 * int matchCount = -1;
 * List&lt;String&gt; matches = Collections.emptyList();
 * try {
 *   matchCount = d.getMatchCount();
 * } catch (InfiniteMatchException inf) {
 *   // Don't care
 * }
 * // Compare against a QUOTA to prevent excessive memory use
 * if (matchCount != -1 && matchCount &lt; QUOTA) {
 *   try {
 *     matches = d.getMatches();
 *   } catch (InfiniteMatchException inf) {
 *    // Don't care
 *   }
 * }
 * if (!matches.isEmpty()) {
 *   // Process matches here
 * }
 * </pre>
 * 
 * </blockquote>
 */
/*-
 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
public class PatternMatchGatherer implements Serializable {
	private static final long serialVersionUID = 1L;

	private String pattern;

	private transient PatternNode root;

	private transient int numberedGroups;

	/**
	 * Constructs a <tt>PatternMatchGatherer</tt> that is to deconstruct the
	 * given <code>pattern</code> into the strings that match it.
	 * 
	 * @param pattern
	 *            the pattern to be deconstructed by the new
	 *            <tt>PatternMatchGatherer</tt>
	 */
	public PatternMatchGatherer(final String pattern) throws PatternSyntaxException, UnsupportedOperationException {
		Pattern.compile(pattern); // ensure that the pattern's syntax is OK

		expression(pattern, new Cursor());

		root.simplify();
	}

	protected void expression(final String pattern, final Cursor cursor) throws UnsupportedOperationException {
		root = alternationList(pattern, cursor);
	}

	protected PatternNode alternationList(final String pattern, final Cursor cursor) throws UnsupportedOperationException {
		final AlternationListNode alternationList = new AlternationListNode();
		subexpression(pattern, alternationList.newAlternation(), cursor);
		while (cursor.in(pattern) && cursor.current(pattern) == '|') {
			cursor.advance();
			subexpression(pattern, alternationList.newAlternation(), cursor);
		}
		return alternationList;
	}

	/**
	 * Processes a stream of characters representing an alternation within an
	 * alternation list (<tt>a|b|c</tt>) and/or a group (<tt>(a)</tt>).
	 * 
	 * @param pattern
	 *            The pattern containing the characters.
	 * @param nodes
	 * @param cursor
	 *            A cursor, initially pointing towards the first character in
	 *            the alternation list or group, updated by this method to point
	 *            after the matched character escape.
	 * @throws UnsupportedOperationException
	 */
	protected void subexpression(final String pattern, final List<PatternNode> nodes, final Cursor cursor) throws UnsupportedOperationException {
		loop: while (cursor.in(pattern)) {
			PatternNode node;
			char cur = cursor.current(pattern);
			switch (cur) {
			case '|':
			case ')':
				break loop;
			case '\\':
				cursor.advance();
				node = escapedCharacter(pattern, cursor);
				break;
			case '.':
				cursor.advance();
				node = new AnyNode();
				break;
			case '[':
				cursor.advance();
				node = characterClass(pattern, cursor);
				break;
			case '^':
				cursor.advance();
				node = new StartStringNode();
				break;
			case '$':
				cursor.advance();
				node = new EndStringNode();
				break;
			case '(':
				cursor.advance();
				node = group(pattern, cursor);
				break;
			default:
				cursor.advance();
				node = new CharacterNode(cur);
			}
			if (cursor.in(pattern)) {
				cur = cursor.current(pattern);
				switch (cur) {
				case '?':
					// Bounded repetition: 0 or 1
					cursor.advance();
					if (cursor.in(pattern)) {
						cur = cursor.current(pattern);
						if (cur == '?' || cur == '+') {
							// ??, ?+: don't care, but skip that anyway
							cursor.advance();
						}
					}
					node = new OptionalNode(node);
					break;
				case '+':
				case '*':
					cursor.advance();
					node = new UnboundedNode();
					if (cursor.in(pattern)) {
						cur = cursor.current(pattern);
						if (cur == '?' || cur == '+') {
							// +?, ++, *?, *+: don't care, but skip that anyway
							cursor.advance();
						}
					}
					break;
				case '{':
					cursor.advance();
					// Required Digit+ (minimum repetition count)
					final StringBuilder min = new StringBuilder();
					StringBuilder max = new StringBuilder();
					while ((cur = cursor.current(pattern)) != ',' && cur != '}') {
						min.append(cur);
						cursor.advance();
					}
					if (cursor.current(pattern) == '}') {
						// Exact repetition count. Make max == min, then skip }.
						max = min;
						cursor.advance();
					} else {
						cursor.advance(); // Skip the ,
						// Optional Digit+ (maximum repetition count)
						while ((cur = cursor.current(pattern)) != '}') {
							max.append(cur);
							cursor.advance();
						}
						cursor.advance(); // Skip the }
					}

					if (cursor.in(pattern)) {
						cur = cursor.current(pattern);
						if (cur == '?' || cur == '+') {
							// {}?, {}+: don't care, but skip that anyway
							cursor.advance();
						}
					}

					if (max.length() > 0) {
						final int minI = Integer.parseInt(min.toString()), maxI = Integer.parseInt(max.toString());
						node = new RepetitionNode(node, minI, maxI);
					} else {
						// No max; sorry, but that's unbounded!
						node = new UnboundedNode();
					}
					break;
				}
			}
			nodes.add(node);
		}
	}

	/**
	 * Processes a character escape (<tt>\x</tt>).
	 * 
	 * @param pattern
	 *            The pattern containing the character escape.
	 * @param cursor
	 *            A cursor, initially pointing towards the character after
	 *            <tt>\</tt>, updated by this method to point after the matched
	 *            character escape.
	 * @return a <tt>PatternNode</tt> for the character escape
	 * @throws UnsupportedOperationException
	 *             if a POSIX character class, invalid control character (
	 *             <tt>\cX</tt>) or unrecognised anchor is met
	 */
	protected PatternNode escapedCharacter(final String pattern, final Cursor cursor) throws UnsupportedOperationException {
		switch (cursor.current(pattern)) {
		case '0':
			// Octal value.
			cursor.advance();
			StringBuilder value = new StringBuilder(3);
			// One mandatory digit.
			value.append(cursor.current(pattern));
			cursor.advance();
			// One optional digit between 0 and 7.
			char cur;
			if (cursor.in(pattern) && (cur = cursor.current(pattern)) >= '0' && cur <= '7') {
				cursor.advance();
				value.append(cur);
				// If the first digit is between 0 and 3, another digit between
				// 0 and 7 is acceptable.
				if (value.charAt(0) >= '0' && value.charAt(0) <= '3' && cursor.in(pattern) && (cur = cursor.current(pattern)) >= '0' && cur <= '7') {
					cursor.advance();
					value.append(cur);
				}
			}
			return new CharacterNode((char) Integer.parseInt(value.toString(), 8));
		case 'x':
			// Hexadecimal value. Two mandatory digits.
			cursor.advance();
			char[] cc = new char[2];
			for (int i = 0; i < 2; i++) {
				cc[i] = cursor.current(pattern);
				cursor.advance();
			}
			return new CharacterNode((char) Integer.parseInt(new String(cc), 16));
		case 'u':
			// Hexadecimal Unicode value. Four mandatory digits.
			cursor.advance();
			cc = new char[4];
			for (int i = 0; i < 4; i++) {
				cc[i] = cursor.current(pattern);
				cursor.advance();
			}
			return new CharacterNode((char) Integer.parseInt(new String(cc), 16));
		case 't':
			cursor.advance();
			return new CharacterNode('\u0009');
		case 'n':
			cursor.advance();
			return new CharacterNode('\n');
		case 'r':
			cursor.advance();
			return new CharacterNode('\r');
		case 'f':
			cursor.advance();
			return new CharacterNode('\f');
		case 'a':
			cursor.advance();
			return new CharacterNode('\u0007');
		case 'e':
			cursor.advance();
			return new CharacterNode('\u001B');
		case 'c':
			cursor.advance();
			if ((cur = cursor.current(pattern)) >= 'A' && cur <= 'Z') {
				return new CharacterNode((char) (cur - '@'));
			} else
				throw new UnsupportedOperationException("Control character out of the range A-Z");
		case 'd':
			cursor.advance();
			return characterClass("[0-9]", new Cursor(1));
		case 'D':
			cursor.advance();
			return characterClass("[^0-9]", new Cursor(1));
		case 's':
			cursor.advance();
			return characterClass("[ \t\n\u000B\f\r]", new Cursor(1));
		case 'S':
			cursor.advance();
			return characterClass("[^ \t\n\u000B\f\r]", new Cursor(1));
		case 'w':
			cursor.advance();
			return characterClass("[a-zA-Z_0-9]", new Cursor(1));
		case 'W':
			cursor.advance();
			return characterClass("[^a-zA-Z_0-9]", new Cursor(1));
		case 'p':
		case 'P':
			throw new UnsupportedOperationException("POSIX character class");
		case 'b':
		case 'B':
			throw new UnsupportedOperationException("Word boundary");
		case 'G':
		case 'Z':
			throw new UnsupportedOperationException("Anchor based on the extremities of a match");
		case 'A':
			cursor.advance();
			return new StartStringNode();
		case 'z':
			cursor.advance();
			return new EndStringNode();
		case '1':
		case '2':
		case '3':
		case '4':
		case '5':
		case '6':
		case '7':
		case '8':
		case '9':
			// Backreferences
			value = new StringBuilder();
			// One mandatory digit.
			value.append(cursor.current(pattern));
			cursor.advance();
			while (cursor.in(pattern) && (cur = cursor.current(pattern)) >= '0' && cur <= '9') {
				value.append(cur);
				if (Integer.parseInt(value.toString()) > numberedGroups) {
					// Not enough capturing groups to satisfy this
					// backreference. Back off and exit the loop.
					value.deleteCharAt(value.length() - 1);
				} else {
					cursor.advance();
					value.append(cur);
				}
			}
			return new BackreferenceNode(Integer.parseInt(value.toString()));
		case 'Q':
			cursor.advance();
			return quoted(pattern, cursor);
		default:
			final PatternNode result = new CharacterNode(cursor.current(pattern));
			cursor.advance();
			return result;
		}
	}

	/**
	 * Returns a node for the characters between <tt>\Q</tt> and <tt>\E</tt>.
	 * 
	 * @param pattern
	 *            The pattern containing the quoted string to process.
	 * @param cursor
	 *            A cursor, initially pointing towards the character after
	 *            <tt>Q</tt>, updated by this method to point after the closing
	 *            <tt>E</tt> of the quoting.
	 * @return a <tt>PatternNode</tt> for the quoted characters
	 */
	protected PatternNode quoted(final String pattern, final Cursor cursor) {
		final StringBuilder quotedString = new StringBuilder();
		while (cursor.in(pattern)) {
			if (cursor.current(pattern) == '\\') {
				cursor.advance();
				if (cursor.current(pattern) == 'E') {
					cursor.advance();
					break;
				} else
					quotedString.append('\\');
			} else {
				quotedString.append(cursor.current(pattern));
				cursor.advance();
			}
		}
		return new StringNode(quotedString.toString());
	}

	/**
	 * Processes a character class (<tt>[]</tt>).
	 * 
	 * @param pattern
	 *            The pattern containing the character class to process.
	 * @param cursor
	 *            A cursor, initially pointing towards the character after
	 *            <tt>[</tt>, updated by this method to point after the closing
	 *            <tt>]</tt> of the character class.
	 * @return a <tt>PatternNode</tt> for the character class
	 * @throws UnsupportedOperationException
	 *             if a POSIX character class or invalid control character (
	 *             <tt>\cX</tt>) is met (as specified in
	 *             <code>escapedCharacterInClass</code>)
	 */
	protected PatternNode characterClass(final String pattern, final Cursor cursor) throws UnsupportedOperationException {
		boolean not = false;
		if (cursor.current(pattern) == '^') {
			not = true;
			cursor.advance();
		}
		final BitSet matched = new BitSet(65536);
		boolean firstChar = true, hasRangeFirst = false;
		char rangeFirst = '\0';
		char cur;
		loop: while (!((cur = cursor.current(pattern)) == ']' && !firstChar)) {
			switch (cur) {
			case '[':
				throw new UnsupportedOperationException("Nested character class");
			case '-':
				if (hasRangeFirst) {
					cursor.advance();
					if (cursor.current(pattern) == ']') {
						matched.set('-');
						break loop;
					} else {
						if (cursor.current(pattern) == '\\') {
							// Try for an escape. Ignore the characters matched.
							// We require an escape that matches only one
							// character.
							cursor.advance();
							final int escapedChar = escapedCharacterInClass(pattern, new BitSet(65536), new Cursor(cursor));
							// If we don't get that, then it's not a range, e.g.
							// [\d-\w]. Match -, then whatever the escape
							// matches.
							if (escapedChar == -1) {
								matched.set('-');
								escapedCharacterInClass(pattern, matched, cursor);
								hasRangeFirst = false;
							} else {
								matched.set(rangeFirst + 1, escapedChar + 1);
								hasRangeFirst = false;
								cursor.advance();
							}
						} else {
							matched.set(rangeFirst + 1, cursor.current(pattern) + 1);
							hasRangeFirst = false;
							cursor.advance();
						}
					}
				} else {
					rangeFirst = '-';
					hasRangeFirst = true;
					matched.set('-');
					cursor.advance();
				}
				break;
			case '\\':
				cursor.advance();
				final int escapedChar = escapedCharacterInClass(pattern, matched, cursor);
				if (escapedChar != -1) {
					hasRangeFirst = true;
					rangeFirst = (char) escapedChar;
				}
				break;
			default:
				cursor.advance();
				matched.set(cur);
				hasRangeFirst = true;
				rangeFirst = cur;
			}
			firstChar = false;
		}
		if (not)
			matched.flip(0, 0x10000);
		cursor.advance(); // Skip the ].
		return new CharacterClassNode(matched);
	}

	/**
	 * Processes a character escape in a character class (<tt>[\x]</tt>).
	 * 
	 * @param pattern
	 *            The pattern containing the character class to process a
	 *            character escape from.
	 * @param matched
	 *            The set of characters matched by prior characters in the
	 *            character class, into which characters matched additionally by
	 *            this character escape are placed.
	 * @param cursor
	 *            A cursor, initially pointing towards the character after
	 *            <tt>\</tt>, updated by this method to point after the matched
	 *            character escape.
	 * @return the character matched by the escape, for range-forming purposes
	 *         in <code>characterClass</code> (for example, in <tt>[\n-\r]</tt>,
	 *         or <code>-1</code> if more than one character is matched by the
	 *         character escape
	 * @throws UnsupportedOperationException
	 *             if a POSIX character class or invalid control character (
	 *             <tt>\cX</tt>) is met
	 */
	protected int escapedCharacterInClass(final String pattern, final BitSet matched, final Cursor cursor) throws UnsupportedOperationException {
		switch (cursor.current(pattern)) {
		case '0':
			// Octal value.
			cursor.advance();
			final StringBuilder value = new StringBuilder(3);
			// One mandatory digit.
			value.append(cursor.current(pattern));
			cursor.advance();
			// One optional digit between 0 and 7.
			char cur;
			if (cursor.in(pattern) && (cur = cursor.current(pattern)) >= '0' && cur <= '7') {
				cursor.advance();
				value.append(cur);
				// If the first digit is between 0 and 3, another digit between
				// 0 and 7 is acceptable.
				if (value.charAt(0) >= '0' && value.charAt(0) <= '3' && cursor.in(pattern) && (cur = cursor.current(pattern)) >= '0' && cur <= '7') {
					cursor.advance();
					value.append(cur);
				}
			}
			int result = Integer.parseInt(value.toString(), 8);
			matched.set(result);
			return result;
		case 'x':
			// Hexadecimal value. Two mandatory digits.
			cursor.advance();
			char[] cc = new char[2];
			for (int i = 0; i < 2; i++) {
				cc[1] = cursor.current(pattern);
				cursor.advance();
			}
			result = Integer.parseInt(new String(cc), 16);
			matched.set(result);
			return result;
		case 'u':
			// Hexadecimal Unicode value. Four mandatory digits.
			cursor.advance();
			cc = new char[4];
			for (int i = 0; i < 4; i++) {
				cc[1] = cursor.current(pattern);
				cursor.advance();
			}
			result = Integer.parseInt(new String(cc), 16);
			matched.set(result);
			return result;
		case 't':
			cursor.advance();
			matched.set('\t');
			return '\t';
		case 'n':
			cursor.advance();
			matched.set('\n');
			return '\n';
		case 'r':
			cursor.advance();
			matched.set('\r');
			return '\r';
		case 'f':
			cursor.advance();
			matched.set('\f');
			return '\f';
		case 'a':
			cursor.advance();
			matched.set('\u0007');
			return '\u0007';
		case 'e':
			cursor.advance();
			matched.set('\u001B');
			return '\u001B';
		case 'c':
			cursor.advance();
			if ((cur = cursor.current(pattern)) >= 'A' && cur <= 'Z') {
				result = cur - '@';
				matched.set(result);
				return result;
			} else
				throw new UnsupportedOperationException("Control character out of the range A-Z");
		case 'd':
			cursor.advance();
			matched.set('0', '9' + 1);
			return -1;
		case 'D':
			cursor.advance();
			final BitSet negatedDigit = new BitSet(65536);
			negatedDigit.set(0, 0x10000);
			negatedDigit.clear('0', '9' + 1);
			matched.or(negatedDigit);
			return -1;
		case 's':
			cursor.advance();
			matched.set(' ');
			matched.set('\t');
			matched.set('\n');
			matched.set('\u000B');
			matched.set('\f');
			matched.set('\r');
			return -1;
		case 'S':
			cursor.advance();
			final BitSet negatedSpace = new BitSet(65536);
			negatedSpace.set(0, 0x10000);
			negatedSpace.clear(' ');
			negatedSpace.clear('\t');
			negatedSpace.clear('\n');
			negatedSpace.clear('\u000B');
			negatedSpace.clear('\f');
			negatedSpace.clear('\r');
			matched.or(negatedSpace);
			return -1;
		case 'w':
			cursor.advance();
			matched.set('a', 'z' + 1);
			matched.set('A', 'Z' + 1);
			matched.set('_');
			matched.set('0', '9' + 1);
			return -1;
		case 'W':
			cursor.advance();
			final BitSet negatedWord = new BitSet(65536);
			negatedWord.set(0, 0x10000);
			negatedWord.clear('a', 'z' + 1);
			negatedWord.clear('A', 'Z' + 1);
			negatedWord.clear('_');
			negatedWord.clear('0', '9' + 1);
			negatedWord.or(negatedWord);
			return -1;
		case 'p':
		case 'P':
			throw new UnsupportedOperationException("POSIX character class");
		default:
			result = cursor.current(pattern);
			matched.set(result);
			cursor.advance();
			return result;
		}
	}

	/**
	 * Processes a group (<tt>()</tt>).
	 * 
	 * @param pattern
	 *            The pattern containing the group to process.
	 * @param cursor
	 *            A cursor, initially pointing towards the character after
	 *            <tt>(</tt>, updated by this method to point after the closing
	 *            <tt>)</tt> of the group.
	 * @return a <tt>PatternNode</tt> for the group
	 */
	protected PatternNode group(final String pattern, final Cursor cursor) throws UnsupportedOperationException {
		boolean capturing = true;
		if (cursor.current(pattern) == '?') {
			capturing = false;
			cursor.advance();
			if (cursor.current(pattern) != ':')
				throw new UnsupportedOperationException("Regex extension (?" + cursor.current(pattern) + ")");
			cursor.advance();
		}

		int groupNumber = 0;
		if (capturing) {
			/*
			 * Since capturing groups are added in left-to-right order of the
			 * opening parentheses, '(', we need to get a number here.
			 * Otherwise, the inner group() would return first, getting an
			 * earlier number for itself, reversing that order.
			 */
			groupNumber = ++numberedGroups;
		}
		PatternNode result = alternationList(pattern, cursor);
		cursor.advance(); // Skip over ).
		if (capturing)
			result = new GroupNode(result, groupNumber);
		return result;
	}

	/**
	 * Returns the approximate number of strings that match this
	 * <tt>PatternMatchGatherer</tt>'s pattern, clamped to
	 * <code>Integer.MAX_VALUE</code>. Note that the number is optimistically
	 * estimated according to the number of matches that are considered possible
	 * before the matches are generated; the actual number may be less if there
	 * are some <i>impossible matches</i> (see the class documentation for
	 * details on impossibility).
	 * 
	 * @return the number of strings that match this
	 *         <tt>PatternMatchGatherer</tt>'s pattern, clamped to
	 *         <code>Integer.MAX_VALUE</code>
	 * @throws InfiniteMatchException
	 *             if the expression can be matched by an <i>infinite set</i> of
	 *             strings (see the class documentation for details on infinity)
	 */
	public int getMatchCount() throws InfiniteMatchException {
		return root.possibilityCount();
	}

	/**
	 * Returns the list of strings matching this <tt>PatternMatchGatherer</tt>'s
	 * pattern. If the expression is <i>impossible</i> (see the class
	 * documentation for details on impossibility), an empty list is returned.
	 * This may occur despite <code>getMatchCount</code> returning more than 0.
	 * 
	 * @return the list of strings matching this <tt>PatternMatchGatherer</tt>'s
	 *         pattern
	 * @throws InfiniteMatchException
	 *             if the expression can be matched by an <i>infinite set</i> of
	 *             strings (see the class documentation for details on infinity)
	 */
	public List<String> getMatches() throws InfiniteMatchException {
		getMatchCount();
		try {
			final List<MatchString> intermediate = root.possibilities();
			final List<String> result = new ArrayList<String>(intermediate.size());
			// Require start and end of string. Otherwise, matches for the
			// the expression are infinite.
			for (final MatchString match : intermediate)
				if (match.isStartMet() && match.getEndLength() == match.length())
					result.add(match.toString());
				else
					throw new InfiniteMatchException("Anchor missing (^ or $) on a branch of the expression");
			return result;
		} catch (final ImpossibleMatchException e) {
			return Collections.emptyList();
		}
	}

	@Override
	public String toString() {
		return root.toString();
	}

	private void readObject(final java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();

		Pattern.compile(pattern); // ensure that the pattern's syntax is OK

		expression(pattern, new Cursor());

		root.simplify();
	}

	private static class AlternationListNode extends PatternNode {
		private final List<List<PatternNode>> alternations = new ArrayList<List<PatternNode>>();

		public List<PatternNode> newAlternation() {
			final List<PatternNode> result = new ArrayList<PatternNode>();
			alternations.add(result);
			return result;
		}

		public int getAlternationCount() {
			return alternations.size();
		}

		public List<PatternNode> getAlternation(final int i) {
			return alternations.get(i);
		}

		@Override
		public String toString() {
			final StringBuilder result = new StringBuilder(alternations.get(0).toString());
			for (int i = 1; i < alternations.size(); i++) {
				result.append(" or ");
				result.append(alternations.get(i).toString());
			}
			return result.toString();
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			int sum = 0;
			for (final List<PatternNode> alternation : alternations) {
				int possibilityCount = 1;
				for (final PatternNode node : alternation)
					possibilityCount = Utils.saturatingMultiply(possibilityCount, node.possibilityCount());
				sum = Utils.saturatingAdd(sum, possibilityCount);
			}
			return sum;
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final List<MatchString> result = new ArrayList<MatchString>();
			for (final List<PatternNode> alternation : alternations) {
				List<MatchString> alternationMatches = new ArrayList<MatchString>();
				// Start off by saying that there's 1 match.
				// If there were 0, multiplying by the rest of the expression
				// would
				// yield nothing.
				alternationMatches.add(new MatchString());
				for (final PatternNode node : alternation) {
					alternationMatches = Utils.multiply(alternationMatches, node.possibilities());
				}
				result.addAll(alternationMatches);
			}
			return result;
		}

		@Override
		public void simplify() {
			for (final List<PatternNode> alternation : alternations) {
				for (final PatternNode node : alternation)
					node.simplify();
				for (int i = 0; i < alternation.size(); i++) {
					if (i > 0 && alternation.get(i) instanceof CharacterNode && alternation.get(i - 1) instanceof CharacterNode) {
						alternation.set(i - 1, new StringNode(new String(new char[] { ((CharacterNode) alternation.get(i - 1)).getCharacter(), ((CharacterNode) alternation.get(i)).getCharacter() })));
						alternation.remove(i);
						i--;
					} else if (i > 0 && alternation.get(i) instanceof CharacterNode && alternation.get(i - 1) instanceof StringNode) {
						alternation.set(i - 1, new StringNode(((StringNode) alternation.get(i - 1)).getString() + ((CharacterNode) alternation.get(i)).getCharacter()));
						alternation.remove(i);
						i--;
					} else if (alternation.get(i) instanceof StringNode && ((StringNode) alternation.get(i)).getString().length() == 0) {
						alternation.remove(i);
						i--;
					} else if (alternation.get(i) instanceof AlternationListNode) {
						final AlternationListNode subalternation = (AlternationListNode) alternation.get(i);
						if (subalternation.getAlternationCount() == 1) {
							alternation.remove(i);
							alternation.addAll(i, subalternation.getAlternation(0));
							i += subalternation.getAlternation(0).size() - 1;
						}
					}
				}
			}
		}
	}

	private static class AnyNode extends PatternNode {
		@Override
		public String toString() {
			return "any character";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final List<MatchString> result = new ArrayList<MatchString>(0x10000);
			for (int i = 0; i < 0x10000; i++) {
				final MatchString cur = new MatchString();
				cur.append((char) i);
				result.add(cur);
			}
			return result;
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 0x10000;
		}
	}

	private static class BackreferenceNode extends PatternNode {
		private final int n;

		public BackreferenceNode(final int n) {
			this.n = n;
		}

		@Override
		public String toString() {
			return "what group " + n + " matched";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final MatchString match = new MatchString();
			match.insertBackreference(n);
			return Collections.singletonList(match);
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 1;
		}
	}

	private static class CharacterClassNode extends PatternNode {
		private final char[] cc;

		public CharacterClassNode(final BitSet cc) {
			this.cc = new char[cc.cardinality()];
			int n = 0;
			for (int i = cc.nextSetBit(0); i < 0x10000 && i >= 0; i = cc.nextSetBit(i + 1)) {
				this.cc[n++] = (char) i;
			}
		}

		@Override
		public String toString() {
			return "either of " + cc.length + " characters";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final List<MatchString> result = new ArrayList<MatchString>(cc.length);
			for (int i = 0; i < cc.length; i++) {
				final MatchString cur = new MatchString();
				cur.append(cc[i]);
				result.add(cur);
			}
			return result;
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return cc.length;
		}
	}

	private static class CharacterNode extends PatternNode {
		private final char c;

		public CharacterNode(final char c) {
			this.c = c;
		}

		public char getCharacter() {
			return c;
		}

		@Override
		public String toString() {
			return "'" + c + "'";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final MatchString match = new MatchString();
			match.append(c);
			return Collections.singletonList(match);
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 1;
		}
	}

	private static class Cursor {
		private int value;

		public Cursor() {}

		public Cursor(final int value) {
			if (value < 0)
				throw new IllegalArgumentException("negative starting value for cursor: " + value);
			this.value = value;
		}

		/**
		 * Copy constructor.
		 */
		public Cursor(final Cursor cursor) {
			value = cursor.value;
		}

		/**
		 * Returns whether the cursor is a valid index for a character in the
		 * given string.
		 * 
		 * @param s
		 *            The string to test.
		 * @return <code>true</code> if the cursor is a valid index for a
		 *         character in the given string; <code>false</code> otherwise
		 */
		public synchronized boolean in(final String s) {
			return value < s.length();
		}

		/**
		 * Returns the character of the given <code>pattern</code> at the index
		 * designated by this <tt>PatternParserContext</tt>'s cursor.
		 * 
		 * @param s
		 *            The pattern to get a character from.
		 * @return the character of the given <code>pattern</code> at the index
		 *         designated by this <tt>PatternParserContext</tt>'s cursor
		 */
		public synchronized char current(final String s) {
			return s.charAt(value);
		}

		/**
		 * Advances this cursor by 1 away from 0.
		 */
		public synchronized void advance() {
			value++;
			if (value < 0) {
				value--;
				throw new IllegalStateException("overflow");
			}
		}
	}

	private static class EndStringNode extends PatternNode {
		@Override
		public String toString() {
			return "end of string";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final MatchString match = new MatchString();
			match.setEndLength(0);
			return Collections.singletonList(match);
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 1;
		}
	}

	private static class GroupNode extends PatternNode {
		private PatternNode node;

		private final int n;

		public GroupNode(final PatternNode node, final int n) {
			this.node = node;
			this.n = n;
		}

		@Override
		public String toString() {
			return "group " + n + ": " + node;
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			List<MatchString> result = new ArrayList<MatchString>();
			// Start off by saying that there's 1 match in this group.
			// If there were 0, multiplying by the rest of the expression would
			// yield nothing.
			final MatchString match = new MatchString();
			match.activateGroup(n);
			result.add(match);

			result = Utils.multiply(result, node.possibilities());

			for (final MatchString resultMatch : result)
				resultMatch.deactivateGroup(n);
			return result;
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return node.possibilityCount();
		}

		@Override
		public void simplify() {
			node.simplify();
			if (node instanceof AlternationListNode) {
				final AlternationListNode alternationList = (AlternationListNode) node;
				if (alternationList.getAlternationCount() == 1 && alternationList.getAlternation(0).size() == 1)
					node = alternationList.getAlternation(0).get(0);
			}
		}
	}

	public static class ImpossibleMatchException extends Exception {
		private static final long serialVersionUID = 1L;

		public ImpossibleMatchException() {}

		public ImpossibleMatchException(final String message) {
			super(message);
		}

		public ImpossibleMatchException(final Throwable cause) {
			super(cause);
		}

		public ImpossibleMatchException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}

	public static class InfiniteMatchException extends Exception {
		private static final long serialVersionUID = 1L;

		public InfiniteMatchException() {}

		public InfiniteMatchException(final String message) {
			super(message);
		}

		public InfiniteMatchException(final Throwable cause) {
			super(cause);
		}

		public InfiniteMatchException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * The <tt>MatchString</tt> class is a way to store ongoing matches of a
	 * deconstructed regular expression while keeping track of capturing groups
	 * that are active, the text of backreferences, and the points at which the
	 * backreferences need to be inserted into the matched text, if any.
	 * <p>
	 * <tt>MatchString</tt> also keeps track of whether <tt>StartStringNode</tt>
	 * and <tt>EndStringNode</tt> have been met on a branch of the regular
	 * expression. If they haven't, then a <tt>PatternMatchGatherer</tt> can
	 * disregard the match and throw <tt>InfiniteMatchException</tt>.
	 * <p>
	 * The main operation of this class is <code>multiplyInPlace</code>, which
	 * takes all attributes of two <tt>MatchString</tt>s and combines them into
	 * the first one. For example, an ongoing match for <tt>Spider-ona</tt>,
	 * with the <tt>StartStringNode</tt> met, a backreference of "-" for \1 and
	 * an insertion point of 9 for that backreference, can be multiplied by a
	 * <tt>MatchString</tt> having an ongoing match of <tt>""</tt> and an
	 * insertion point of 0 for the first backreference. In that case, the first
	 * <tt>MatchString</tt> would have another insertion point of 10 for the
	 * first backreference added to it. Calling the <code>toString</code> method
	 * on the resulting <tt>MatchString</tt> would return
	 * <code>"Spider-on-a-"</code>.
	 * <p>
	 * The above example would be an intermediate match for the regular
	 * expression <tt>^Spider([- ])on\1a\1stick$</tt>.
	 */
	private static class MatchString {
		/**
		 * The accumulated match.
		 */
		private final StringBuilder s;

		/**
		 * Map of capturing group numbers, associated with what they captured,
		 * in the current branch of the regular expression.
		 */
		private Map<Integer, StringBuilder> backreferences;

		/**
		 * List of backreferences to insert. The point at which a certain
		 * backreference is to be inserted is in
		 * <code>backreferenceInsertionPoints</code> at the same index as in
		 * this list.
		 */
		private List<Integer> backreferenceInsertions;

		/**
		 * List of indices inside the accumulated match (in <code>s</code>) at
		 * which backreferences are to be inserted.
		 */
		private List<Integer> backreferenceInsertionPoints;

		/**
		 * List of capturing groups currently being processed. A string that is
		 * appended to this <tt>MatchString</tt> while a capturing group is
		 * active will be added to all active groups as well as <code>s</code>.
		 */
		private BitSet activeGroups;

		/**
		 * <code>true</code> if <tt>StartStringNode</tt> has been met in the
		 * current branch of the regular expression.
		 * <tt>PatternMatchGatherer</tt> uses this information to detect missing
		 * anchors.
		 */
		private boolean startMet;

		/**
		 * A value other than -1 if <tt>EndStringNode</tt> has been met in the
		 * current branch of the regular expression, in which case the value is
		 * the expected length of the string. <tt>PatternMatchGatherer</tt> uses
		 * this information to detect missing anchors.
		 */
		private int endLength = -1;

		/**
		 * Copy constructor.
		 */
		private MatchString(final MatchString m) {
			s = new StringBuilder(m.s.length());
			s.append(m.s);
			if (m.backreferences != null) {
				backreferences = new TreeMap<Integer, StringBuilder>();
				for (final Map.Entry<Integer, StringBuilder> entry : m.backreferences.entrySet())
					backreferences.put(entry.getKey(), new StringBuilder(entry.getValue()));
			}
			if (m.activeGroups != null)
				activeGroups = (BitSet) m.activeGroups.clone();
			startMet = m.startMet;
			endLength = m.endLength;
			if (m.backreferenceInsertionPoints != null) {
				backreferenceInsertions = new ArrayList<Integer>(m.backreferenceInsertions);
				backreferenceInsertionPoints = new ArrayList<Integer>(m.backreferenceInsertionPoints);
			}
		}

		/**
		 * Creates a new <tt>MatchString</tt> object having the empty string as
		 * its currently accumulated match, no backreferences and no active
		 * capturing groups.
		 */
		public MatchString() {
			s = new StringBuilder(1);
		}

		/**
		 * Creates a new <tt>MatchString</tt> object having the concatenation of
		 * the two arguments' currently accumulated matches, first argument
		 * first, as its currently accumulated match; the union of each
		 * argument's backreferences as its backreferences; and the union of
		 * each argument's active capturing groups as its active capturing
		 * groups. If both arguments have a definition for a certain
		 * backreference, the second argument's definition is preferred.
		 */
		public MatchString(final MatchString multiplicand, final MatchString multiplicator) throws ImpossibleMatchException {
			// Copy constructor with the multiplicand
			this(multiplicand);
			// Tell it to multiply with the multiplicator
			multiplyInPlace(multiplicator);
		}

		/**
		 * Enters the specified group.
		 * 
		 * @param group
		 *            The number of the capturing group to enter.
		 */
		public void activateGroup(final int group) {
			if (backreferences == null)
				backreferences = new TreeMap<Integer, StringBuilder>();
			backreferences.put(group, new StringBuilder());
			if (activeGroups == null)
				activeGroups = new BitSet();
			activeGroups.set(group);
		}

		public String getBackreference(final int group) {
			if (backreferences == null)
				return "";
			final StringBuilder result = backreferences.get(group);
			if (result == null)
				return "";
			else
				return result.toString();
		}

		public void resolveBackreferences() {
			if (backreferenceInsertionPoints != null) {
				while (!backreferenceInsertionPoints.isEmpty()) {
					final int group = backreferenceInsertions.remove(backreferenceInsertions.size() - 1), point = backreferenceInsertionPoints.remove(backreferenceInsertionPoints.size() - 1);
					s.insert(point, getBackreference(group));
				}
				backreferenceInsertionPoints = null;
				backreferenceInsertions = null;
			}
		}

		/**
		 * Multiplies this <tt>MatchString</tt>, in place, with the
		 * <code>multiplicator</code>.
		 * 
		 * @param multiplicator
		 *            The <tt>MatchString</tt> to multiply this one with, its
		 *            contents placed after this <tt>MatchString</tt>'s own.
		 * @throws ImpossibleMatchException
		 *             if the match is now impossible and this
		 *             <tt>MatchString</tt> should be discarded
		 */
		public void multiplyInPlace(final MatchString multiplicator) throws ImpossibleMatchException {
			// Union of backreferences
			if (multiplicator.backreferences != null) {
				if (backreferences == null)
					backreferences = new TreeMap<Integer, StringBuilder>();
				for (final Map.Entry<Integer, StringBuilder> entry : multiplicator.backreferences.entrySet())
					backreferences.put(entry.getKey(), new StringBuilder(entry.getValue()));
			}
			// Union of active groups
			if (multiplicator.activeGroups != null) {
				if (activeGroups == null)
					activeGroups = new BitSet();
				activeGroups.or(multiplicator.activeGroups);
			}
			// Put multiplicator's text into all active groups.
			if (activeGroups != null) {
				if (backreferences == null)
					backreferences = new TreeMap<Integer, StringBuilder>();
				for (int activeGroup = activeGroups.nextSetBit(0); activeGroup >= 0; activeGroup = activeGroups.nextSetBit(activeGroup + 1)) {
					if (backreferences.get(activeGroup) == null)
						backreferences.put(activeGroup, new StringBuilder(multiplicator.s));
					else
						backreferences.get(activeGroup).append(multiplicator.s);
				}
			}
			// Handle double ^ and $
			if (multiplicator.startMet && s.length() > 0)
				throw new ImpossibleMatchException("Start of string impossible at index " + s.length());
			startMet |= multiplicator.startMet;
			if (endLength != -1) {
				if (multiplicator.s.length() > 0)
					throw new ImpossibleMatchException("End of string already expected at index " + s.length());
			} else
				endLength = (multiplicator.endLength == -1 ? -1 : multiplicator.endLength + s.length());
			// Union of backreference insertion points
			if (multiplicator.backreferenceInsertionPoints != null) {
				if (backreferenceInsertionPoints == null) {
					backreferenceInsertions = new ArrayList<Integer>(1);
					backreferenceInsertionPoints = new ArrayList<Integer>(1);
				}
				for (final int group : multiplicator.backreferenceInsertions) {
					if (multiplicator.activeGroups != null && multiplicator.activeGroups.get(group))
						throw new ImpossibleMatchException("Backreference to a group that is being created");
					if (multiplicator.backreferences != null && multiplicator.backreferences.containsKey(group) && multiplicator.backreferences.get(group).toString().contentEquals(multiplicator.s))
						throw new ImpossibleMatchException("Backreference to a group that was created in the multiplicator");
					backreferenceInsertions.add(group);
				}
				for (final int point : multiplicator.backreferenceInsertionPoints)
					backreferenceInsertionPoints.add(point + s.length());
			}
			// String = a's + b's
			s.append(multiplicator.s);
		}

		/**
		 * Exits the specified group.
		 * 
		 * @param group
		 *            The number of the capturing group to exit.
		 */
		public void deactivateGroup(final int group) {
			if (activeGroups != null) {
				activeGroups.clear(group);
				if (activeGroups.isEmpty())
					activeGroups = null;
			}
		}

		/**
		 * Appends the specified character to the current match of this
		 * <tt>MatchString</tt> and all active capturing groups.
		 * 
		 * @param c
		 *            The character to add.
		 */
		public void append(final char c) {
			s.append(c);
			if (activeGroups != null) {
				if (backreferences == null)
					backreferences = new TreeMap<Integer, StringBuilder>();
				for (int activeGroup = activeGroups.nextSetBit(0); activeGroup >= 0; activeGroup = activeGroups.nextSetBit(activeGroup + 1))
					backreferences.get(activeGroup).append(c);
			}
		}

		/**
		 * Appends the specified string to the current match of this
		 * <tt>MatchString</tt> and all active capturing groups.
		 * 
		 * @param s
		 *            The string to add.
		 */
		public void append(final String s) {
			this.s.append(s);
			if (activeGroups != null) {
				if (backreferences == null)
					backreferences = new TreeMap<Integer, StringBuilder>();
				for (int activeGroup = activeGroups.nextSetBit(0); activeGroup >= 0; activeGroup = activeGroups.nextSetBit(activeGroup + 1))
					backreferences.get(activeGroup).append(s);
			}
		}

		/**
		 * Appends a reference to the string matched by the specified capturing
		 * group to this <tt>MatchString</tt>.
		 * 
		 * @param group
		 *            The number of the capturing group to append a reference
		 *            to.
		 * @throws ImpossibleMatchException
		 *             if the capturing group is in the process of being made,
		 *             for example by the regular expression <tt>(\1)</tt>
		 */
		public void insertBackreference(final int group) throws ImpossibleMatchException {
			if (activeGroups != null && activeGroups.get(group))
				throw new ImpossibleMatchException("Backreference to a group that is being created");
			if (backreferenceInsertionPoints == null) {
				backreferenceInsertions = new ArrayList<Integer>(1);
				backreferenceInsertionPoints = new ArrayList<Integer>(1);
			}
			backreferenceInsertions.add(group);
			backreferenceInsertionPoints.add(s.length());
		}

		public void setStartMet(final boolean value) {
			startMet = value;
		}

		public boolean isStartMet() {
			return startMet;
		}

		public int getEndLength() {
			return endLength;
		}

		public void setEndLength(final int value) {
			endLength = value;
		}

		public int length() {
			return s.length();
		}

		@Override
		public String toString() {
			resolveBackreferences();
			return s.toString();
		}

		@Override
		public MatchString clone() {
			return new MatchString(this);
		}
	}

	private static class OptionalNode extends PatternNode {
		private final PatternNode node;

		public OptionalNode(final PatternNode node) {
			this.node = node;
		}

		@Override
		public String toString() {
			return "optional " + node;
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			List<MatchString> result = new ArrayList<MatchString>();
			// Start off by saying that there's 1 match.
			// If there were 0, multiplying by the rest of the expression would
			// yield nothing.
			result.add(new MatchString());
			result = Utils.multiply(result, node.possibilities());
			// Add an empty match, to signify that everything else was optional.
			result.add(new MatchString());
			return result;
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return Utils.saturatingAdd(node.possibilityCount(), 1);
		}

		@Override
		public void simplify() {
			node.simplify();
		}
	}

	private static abstract class PatternNode {
		/**
		 * @return the number of possibilities for matches on this node and its
		 *         children
		 */
		public abstract int possibilityCount() throws InfiniteMatchException;

		/**
		 * @return the list of match possibilities for this <tt>PatternNode</tt>
		 */
		public abstract List<MatchString> possibilities() throws ImpossibleMatchException;

		/**
		 * Optimises this <tt>PatternNode</tt>'s representation.
		 */
		public void simplify() {}
	}

	private static class RepetitionNode extends PatternNode {
		private final PatternNode node;

		private final int minRepetitions, maxRepetitions;

		public RepetitionNode(final PatternNode node, final int minRepetitions, final int maxRepetitions) {
			this.node = node;
			this.minRepetitions = minRepetitions;
			this.maxRepetitions = maxRepetitions;
		}

		@Override
		public String toString() {
			if (minRepetitions == maxRepetitions)
				return node.toString() + " " + minRepetitions + " times";
			return node.toString() + " " + minRepetitions + " to " + maxRepetitions + " times";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final List<MatchString> result = new ArrayList<MatchString>();
			for (int repetitionCount = minRepetitions; repetitionCount <= maxRepetitions; repetitionCount++) {
				List<MatchString> matchesForCount = new ArrayList<MatchString>();
				// Start off by saying that there's 1 match.
				// If there were 0, multiplying by the rest of the expression
				// would yield nothing.
				matchesForCount.add(new MatchString());
				for (int i = 0; i < repetitionCount; i++) {
					matchesForCount = Utils.multiply(matchesForCount, node.possibilities());
				}
				result.addAll(matchesForCount);
			}
			return result;
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			// The number of possibilities grows exponentially.
			// Formula: Sum[Min,Max] (PossibilityCount ^ RepetitionCount)
			int sum = 0;
			final int nodePossibilities = node.possibilityCount();
			for (int repetitionCount = minRepetitions; repetitionCount <= maxRepetitions; repetitionCount++) {
				int partialSum = 1;
				for (int i = 0; i < repetitionCount; i++)
					partialSum = Utils.saturatingMultiply(partialSum, nodePossibilities);
				sum = Utils.saturatingAdd(sum, partialSum);
			}
			return sum;
		}

		@Override
		public void simplify() {
			node.simplify();
		}
	}

	private static class StartStringNode extends PatternNode {
		@Override
		public String toString() {
			return "start of string";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final MatchString match = new MatchString();
			match.setStartMet(true);
			return Collections.singletonList(match);
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 1;
		}
	}

	private static class StringNode extends PatternNode {
		private final String s;

		public StringNode(final String s) {
			this.s = s;
		}

		public String getString() {
			return s;
		}

		@Override
		public String toString() {
			return "\"" + s + "\"";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			final MatchString match = new MatchString();
			match.append(s);
			return Collections.singletonList(match);
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			return 1;
		}
	}

	private static class UnboundedNode extends PatternNode {
		@Override
		public String toString() {
			return "something repeated an infinite number of times";
		}

		@Override
		public List<MatchString> possibilities() throws ImpossibleMatchException {
			throw new ImpossibleMatchException("Infinite matches");
		}

		@Override
		public int possibilityCount() throws InfiniteMatchException {
			throw new InfiniteMatchException("Unbounded repetition");
		}
	}

	private static class Utils {
		public static int saturatingAdd(final int a, final int b) {
			int sum = a + b;
			if (b < 0) {
				// Expect the result to be less than a.
				if (sum > a)
					// If it's not, clamp the result to MIN_VALUE.
					sum = Integer.MIN_VALUE;
			} else if (b > 0) {
				// Expect the result to be more than a.
				if (sum < a)
					// If it's not, clamp the result to MAX_VALUE.
					sum = Integer.MAX_VALUE;
			}
			return sum;
		}

		public static int saturatingMultiply(final int a, final int b) {
			final long product = (long) a * (long) b;
			if (product < Integer.MIN_VALUE)
				return Integer.MIN_VALUE;
			else if (product > Integer.MAX_VALUE)
				return Integer.MAX_VALUE;
			return (int) product;
		}

		public static List<MatchString> multiply(final List<MatchString> multiplicand, final List<MatchString> multiplicator) {
			if (multiplicator.size() == 1) {
				for (final MatchString multiplicator1 : multiplicator)
					for (int i = 0; i < multiplicand.size(); i++) {
						final MatchString multiplicand1 = multiplicand.get(i);
						try {
							multiplicand1.multiplyInPlace(multiplicator1);
						} catch (final ImpossibleMatchException e) {
							// Discard matches that have become impossible
							multiplicand.remove(i);
							i--;
						}
					}
				return multiplicand;
			} else {
				final List<MatchString> result = new ArrayList<MatchString>(saturatingMultiply(multiplicand.size(), multiplicator.size()));
				for (final MatchString multiplicand1 : multiplicand)
					for (final MatchString multiplicator1 : multiplicator)
						try {
							result.add(new MatchString(multiplicand1, multiplicator1));
						} catch (final ImpossibleMatchException e) {
							// Discard matches that have become impossible
						}
				return result;
			}
		}
	}
}