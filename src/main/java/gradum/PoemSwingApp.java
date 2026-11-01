package gradum;

import javax.swing.*;

public class PoemSwingApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Poem");
            JTextArea textArea = new JTextArea(
                "春眠不觉晓，\n" +
                "处处闻啼鸟。\n" +
                "夜来风雨声，\n" +
                "花落知多少。"
            );
            textArea.setEditable(false);
            frame.add(new JScrollPane(textArea));
            frame.setSize(400, 300);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
