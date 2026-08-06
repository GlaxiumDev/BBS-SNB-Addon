package glaxium.snb.model.fbx.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Small recursive-descent reader for the FBX ASCII grammar used by 7.x
 * exporters. It intentionally parses FBX declarations rather than treating
 * the file as JSON: newlines terminate leaf nodes and semicolons are comments.
 */
final class FbxAsciiReader
{
    private static final int MAX_DEPTH = 512;

    private final String source;
    private int position;
    private int line = 1;
    private int column = 1;

    private FbxAsciiReader(byte[] bytes)
    {
        String text = new String(bytes, StandardCharsets.UTF_8);
        this.source = !text.isEmpty() && text.charAt(0) == '\ufeff'
                ? text.substring(1)
                : text;
    }

    static FbxDocument read(byte[] bytes) throws IOException
    {
        return new FbxAsciiReader(bytes).readDocument();
    }

    private FbxDocument readDocument() throws IOException
    {
        FbxDocument document = new FbxDocument();
        skipWhitespaceAndComments();

        while (!atEnd())
        {
            if (peek() == '}')
            {
                throw error("unexpected '}'");
            }

            document.roots.add(readNode(0));
            skipWhitespaceAndComments();
        }

        FbxNode header = document.root("FBXHeaderExtension");
        FbxNode version = header == null ? document.root("FBXVersion") : header.child("FBXVersion");

        if (version != null && !version.properties.isEmpty()
                && version.properties.get(0) instanceof Number number)
        {
            document.version = number.intValue();
        }

        return document;
    }

    private FbxNode readNode(int depth) throws IOException
    {
        if (depth > MAX_DEPTH)
        {
            throw error("FBX node nesting exceeds " + MAX_DEPTH);
        }

        skipWhitespaceAndComments();
        int declarationLine = this.line;
        String name = readNodeName();

        if (name.isEmpty())
        {
            throw error("empty node name on line " + declarationLine);
        }

        expect(':');
        skipHorizontalSpace();

        List<Object> properties = new ArrayList<>();
        List<FbxNode> children = new ArrayList<>();

        if (!atEnd() && peek() == '*')
        {
            properties.add(readArray());
            consumeLineRemainder();
            return new FbxNode(name, properties, children);
        }

        while (!atEnd())
        {
            skipHorizontalSpace();

            if (atEnd())
            {
                break;
            }

            char current = peek();

            if (current == ';')
            {
                skipComment();
                consumeOptionalNewline();

                if (consumeBraceAfterLine())
                {
                    readChildren(children, depth);
                }

                break;
            }
            if (current == '\r' || current == '\n')
            {
                consumeNewline();

                if (consumeBraceAfterLine())
                {
                    readChildren(children, depth);
                }

                break;
            }
            if (current == '{')
            {
                advance();
                readChildren(children, depth);
                break;
            }
            if (current == '}')
            {
                break;
            }
            if (current == ',')
            {
                advance();
                continue;
            }

            properties.add(readValue(false));
        }

        return new FbxNode(name, properties, children);
    }

    private void readChildren(List<FbxNode> children, int parentDepth) throws IOException
    {
        while (true)
        {
            skipWhitespaceAndComments();

            if (atEnd())
            {
                throw error("unterminated child block");
            }
            if (peek() == '}')
            {
                advance();
                return;
            }

            children.add(readNode(parentDepth + 1));
        }
    }

    private Object readArray() throws IOException
    {
        expect('*');
        skipHorizontalSpace();
        String countText = readWhileDigits();

        if (countText.isEmpty())
        {
            throw error("expected an array length after '*'");
        }

        final int declaredCount;

        try
        {
            declaredCount = Integer.parseInt(countText);
        }
        catch (NumberFormatException exception)
        {
            throw error("array length is too large", exception);
        }

        skipWhitespaceAndComments();
        expect('{');
        skipWhitespaceAndComments();

        if (!atEnd() && peek() == '}')
        {
            advance();

            if (declaredCount != 0)
            {
                throw error("array declares " + declaredCount + " values but contains none");
            }

            return new double[0];
        }

        String arrayName = readNodeName();

        if (!arrayName.equals("a"))
        {
            throw error("expected array data node 'a', found '" + arrayName + "'");
        }

        expect(':');
        List<Number> values = new ArrayList<>(Math.min(declaredCount, 65_536));
        boolean floatingPoint = false;

        while (true)
        {
            skipWhitespaceAndComments();

            if (atEnd())
            {
                throw error("unterminated ASCII array");
            }
            if (peek() == '}')
            {
                advance();
                break;
            }
            if (peek() == ',')
            {
                advance();
                continue;
            }

            Object value = readValue(true);

            if (!(value instanceof Number number))
            {
                throw error("ASCII arrays may only contain numbers");
            }

            values.add(number);
            floatingPoint |= number instanceof Double || number instanceof Float;
        }

        if (values.size() != declaredCount)
        {
            throw error("array declares " + declaredCount + " values but contains " + values.size());
        }

        if (floatingPoint)
        {
            double[] result = new double[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = values.get(i).doubleValue();
            return result;
        }

        boolean fitsInt = true;

        for (Number number : values)
        {
            long value = number.longValue();
            fitsInt &= value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
        }

        if (fitsInt)
        {
            int[] result = new int[values.size()];
            for (int i = 0; i < result.length; i++) result[i] = values.get(i).intValue();
            return result;
        }

        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i).longValue();
        return result;
    }

    private Object readValue(boolean arrayValue) throws IOException
    {
        if (peek() == '"')
        {
            return readQuotedString();
        }

        int start = this.position;

        while (!atEnd())
        {
            char current = peek();

            if (current == ',' || current == '}' || current == '{'
                    || current == '\r' || current == '\n' || current == ';'
                    || (arrayValue && Character.isWhitespace(current)))
            {
                break;
            }

            advance();
        }

        String token = this.source.substring(start, this.position).trim();

        if (token.isEmpty())
        {
            throw error("expected a property value");
        }

        return parseBareValue(token);
    }

    private Object parseBareValue(String token)
    {
        if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("t"))
        {
            return Boolean.TRUE;
        }
        if (token.equalsIgnoreCase("false") || token.equalsIgnoreCase("f"))
        {
            return Boolean.FALSE;
        }

        try
        {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0
                    || token.indexOf('E') >= 0
                    || token.equalsIgnoreCase("nan")
                    || token.equalsIgnoreCase("infinity")
                    || token.equalsIgnoreCase("-infinity"))
            {
                return Double.parseDouble(token);
            }

            return Long.parseLong(token);
        }
        catch (NumberFormatException ignored)
        {
            return token;
        }
    }

    private String readQuotedString() throws IOException
    {
        expect('"');
        StringBuilder result = new StringBuilder();

        while (!atEnd())
        {
            char current = advance();

            if (current == '"')
            {
                return result.toString();
            }
            if (current == '\\' && !atEnd())
            {
                char escaped = peek();

                if (escaped == '"' || escaped == '\\')
                {
                    result.append(advance());
                    continue;
                }
            }

            result.append(current);
        }

        throw error("unterminated quoted string");
    }

    private String readNodeName() throws IOException
    {
        skipHorizontalSpace();
        int start = this.position;

        while (!atEnd())
        {
            char current = peek();

            if (current == ':')
            {
                return this.source.substring(start, this.position).trim();
            }
            if (current == '\r' || current == '\n' || current == '{'
                    || current == '}' || current == ';')
            {
                throw error("expected ':' after node name");
            }

            advance();
        }

        throw error("unexpected end of file after node name");
    }

    private boolean consumeBraceAfterLine() throws IOException
    {
        skipWhitespaceAndComments();

        if (!atEnd() && peek() == '{')
        {
            advance();
            return true;
        }

        return false;
    }

    private void consumeLineRemainder() throws IOException
    {
        skipHorizontalSpace();

        if (!atEnd() && peek() == ';')
        {
            skipComment();
        }
        else if (!atEnd() && peek() != '\r' && peek() != '\n' && peek() != '}')
        {
            throw error("unexpected text after ASCII array");
        }

        consumeOptionalNewline();
    }

    private void skipWhitespaceAndComments() throws IOException
    {
        while (!atEnd())
        {
            if (Character.isWhitespace(peek()))
            {
                advance();
            }
            else if (peek() == ';')
            {
                skipComment();
            }
            else
            {
                return;
            }
        }
    }

    private void skipHorizontalSpace()
    {
        while (!atEnd() && (peek() == ' ' || peek() == '\t' || peek() == '\f'))
        {
            advance();
        }
    }

    private void skipComment()
    {
        while (!atEnd() && peek() != '\r' && peek() != '\n')
        {
            advance();
        }
    }

    private void consumeOptionalNewline()
    {
        if (!atEnd() && (peek() == '\r' || peek() == '\n'))
        {
            consumeNewline();
        }
    }

    private void consumeNewline()
    {
        if (!atEnd() && peek() == '\r')
        {
            advance();
            if (!atEnd() && peek() == '\n') advance();
        }
        else if (!atEnd() && peek() == '\n')
        {
            advance();
        }
    }

    private String readWhileDigits()
    {
        int start = this.position;
        while (!atEnd() && Character.isDigit(peek())) advance();
        return this.source.substring(start, this.position);
    }

    private void expect(char expected) throws IOException
    {
        if (atEnd() || peek() != expected)
        {
            throw error("expected '" + expected + "'");
        }

        advance();
    }

    private char peek()
    {
        return this.source.charAt(this.position);
    }

    private char advance()
    {
        char value = this.source.charAt(this.position++);

        if (value == '\n')
        {
            this.line++;
            this.column = 1;
        }
        else
        {
            this.column++;
        }

        return value;
    }

    private boolean atEnd()
    {
        return this.position >= this.source.length();
    }

    private IOException error(String message)
    {
        return new IOException("ASCII FBX at line " + this.line + ", column "
                + this.column + ": " + message);
    }

    private IOException error(String message, Throwable cause)
    {
        return new IOException("ASCII FBX at line " + this.line + ", column "
                + this.column + ": " + message, cause);
    }
}
