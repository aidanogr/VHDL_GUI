package vdml.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

final class VhdlCodeArea extends JTextPane {
    private static final Pattern KEYWORDS = Pattern.compile(
            "(?i)\\b(architecture|begin|end|entity|is|of|signal|constant|type|subtype|component|"
                    + "port|generic|in|out|inout|process|if|then|else|elsif|case|when|others|"
                    + "loop|for|while|generate|library|use|all|after|wait|report|severity|"
                    + "return|function|procedure|variable|shared|transport|inertial|not|and|or|"
                    + "xor|mod|rem|abs)\\b");
    private static final Pattern COMMENTS = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern STRINGS = Pattern.compile("\"(?:[^\"]|\"\")*\"");
    private static final Pattern NUMBERS = Pattern.compile("\\b\\d+(?:_[\\d]+)*(?:#[\\da-fA-F_]+#)?\\b");

    private final SimpleAttributeSet plainStyle = style(new Color(0xe6, 0xe8, 0xef));
    private final SimpleAttributeSet keywordStyle = style(new Color(0x72, 0xa7, 0xff), true);
    private final SimpleAttributeSet commentStyle = style(new Color(0x7f, 0x9c, 0x82));
    private final SimpleAttributeSet stringStyle = style(new Color(0xd6, 0xba, 0x7b));
    private final SimpleAttributeSet numberStyle = style(new Color(0xc5, 0x91, 0xe8));
    private final Highlighter.HighlightPainter errorPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0x71, 0x2f, 0x35));
    private boolean highlighting;
    private boolean rehighlightPending;
    private boolean vimMode;
    private boolean insertMode = true;
    private boolean deletingLine;
    private Consumer<String> modeListener = ignored -> { };

    VhdlCodeArea() {
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        setBackground(new Color(0x1e, 0x20, 0x25));
        setForeground(new Color(0xe6, 0xe8, 0xef));
        setCaretColor(Color.WHITE);
        setSelectionColor(new Color(0x36, 0x5f, 0x91));
        setSelectedTextColor(Color.WHITE);
        setMargin(new Insets(8, 10, 8, 10));
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleRehighlight();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleRehighlight();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleRehighlight();
            }
        });
    }

    void setVimMode(boolean enabled) {
        vimMode = enabled;
        insertMode = !enabled;
        modeListener.accept(modeText());
    }

    void setModeListener(Consumer<String> listener) {
        modeListener = listener == null ? ignored -> { } : listener;
        modeListener.accept(modeText());
    }

    void setErrorLine(int line) {
        getHighlighter().removeAllHighlights();
        if (line < 1) {
            return;
        }
        Element root = getDocument().getDefaultRootElement();
        if (line > root.getElementCount()) {
            return;
        }
        Element element = root.getElement(line - 1);
        int start = element.getStartOffset();
        int end = Math.min(element.getEndOffset(), getDocument().getLength());
        if (start >= end) {
            return;
        }
        try {
            getHighlighter().addHighlight(start, end, errorPainter);
        } catch (BadLocationException ex) {
            throw new IllegalStateException("Could not highlight the VHDL diagnostic.", ex);
        }
    }

    private static SimpleAttributeSet style(Color color) {
        return style(color, false);
    }

    private static SimpleAttributeSet style(Color color, boolean bold) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setBold(attributes, bold);
        return attributes;
    }

    private String modeText() {
        return vimMode ? insertMode ? "Vim: INSERT" : "Vim: NORMAL" : "Vim: off";
    }

    private void scheduleRehighlight() {
        if (highlighting || rehighlightPending) {
            return;
        }
        rehighlightPending = true;
        SwingUtilities.invokeLater(() -> {
            rehighlightPending = false;
            rehighlight();
        });
    }

    private void rehighlight() {
        if (highlighting) {
            return;
        }
        highlighting = true;
        try {
            StyledDocument document = getStyledDocument();
            String text = document.getText(0, document.getLength());
            document.setCharacterAttributes(0, text.length(), plainStyle, true);
            applyPattern(document, KEYWORDS, text, keywordStyle);
            applyPattern(document, NUMBERS, text, numberStyle);
            applyPattern(document, STRINGS, text, stringStyle);
            applyPattern(document, COMMENTS, text, commentStyle);
        } catch (BadLocationException ex) {
            throw new IllegalStateException("Could not highlight VHDL source.", ex);
        } finally {
            highlighting = false;
        }
    }

    private void applyPattern(StyledDocument document, Pattern pattern, String text,
                              SimpleAttributeSet attributes) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            document.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), attributes, false);
        }
    }

    @Override
    protected void processKeyEvent(KeyEvent event) {
        if (!vimMode) {
            super.processKeyEvent(event);
            return;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            insertMode = false;
            modeListener.accept(modeText());
            event.consume();
            return;
        }
        if (insertMode) {
            super.processKeyEvent(event);
            return;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            handleNormalKey(event);
        }
        event.consume();
    }

    private void handleNormalKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (deletingLine) {
            deletingLine = false;
            if (key == KeyEvent.VK_D) {
                deleteCurrentLine();
                return;
            }
        }
        switch (key) {
            case KeyEvent.VK_I -> setInsertMode(false);
            case KeyEvent.VK_A -> {
                setCaretPosition(Math.min(getDocument().getLength(), getCaretPosition() + 1));
                setInsertMode(false);
            }
            case KeyEvent.VK_H -> moveCaret(-1);
            case KeyEvent.VK_L -> moveCaret(1);
            case KeyEvent.VK_J -> moveVertical(1);
            case KeyEvent.VK_K -> moveVertical(-1);
            case KeyEvent.VK_0, KeyEvent.VK_HOME -> moveToLineBoundary(false);
            case KeyEvent.VK_4 -> {
                if (event.isShiftDown()) {
                    moveToLineBoundary(true);
                }
            }
            case KeyEvent.VK_X -> deleteCharacter();
            case KeyEvent.VK_D -> deletingLine = true;
            default -> {
            }
        }
    }

    private void setInsertMode(boolean append) {
        insertMode = true;
        if (append && getCaretPosition() < getDocument().getLength()) {
            setCaretPosition(getCaretPosition() + 1);
        }
        modeListener.accept(modeText());
    }

    private void moveCaret(int offset) {
        setCaretPosition(Math.max(0, Math.min(getDocument().getLength(), getCaretPosition() + offset)));
    }

    private void moveVertical(int direction) {
        Element root = getDocument().getDefaultRootElement();
        int position = getCaretPosition();
        int line = root.getElementIndex(position);
        int column = position - lineStartOffset(root, line);
        int targetLine = Math.max(0, Math.min(root.getElementCount() - 1, line + direction));
        int target = Math.min(lineStartOffset(root, targetLine) + column,
                lineEndOffset(root, targetLine));
        setCaretPosition(target);
    }

    private void moveToLineBoundary(boolean end) {
        Element root = getDocument().getDefaultRootElement();
        int line = root.getElementIndex(getCaretPosition());
        setCaretPosition(end ? lineEndOffset(root, line) : lineStartOffset(root, line));
    }

    private void deleteCharacter() {
        int position = getCaretPosition();
        if (position >= getDocument().getLength()) {
            return;
        }
        try {
            getDocument().remove(position, 1);
        } catch (BadLocationException ex) {
            throw new IllegalStateException("Could not delete VHDL source.", ex);
        }
    }

    private void deleteCurrentLine() {
        try {
            Element root = getDocument().getDefaultRootElement();
            int line = root.getElementIndex(getCaretPosition());
            int start = lineStartOffset(root, line);
            int end = lineEndOffset(root, line);
            if (end < getDocument().getLength()) {
                end++;
            }
            getDocument().remove(start, end - start);
            setCaretPosition(Math.min(start, getDocument().getLength()));
        } catch (BadLocationException ex) {
            throw new IllegalStateException("Could not delete the VHDL line.", ex);
        }
    }

    private static int lineStartOffset(Element root, int line) {
        return root.getElement(line).getStartOffset();
    }

    private int lineEndOffset(Element root, int line) {
        return Math.min(root.getElement(line).getEndOffset(), getDocument().getLength());
    }
}
