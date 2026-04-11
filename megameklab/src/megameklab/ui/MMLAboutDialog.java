package megameklab.ui;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.client.ui.dialogs.AbstractAboutDialog;
import megameklab.MMLConstants;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;

public class MMLAboutDialog extends AbstractAboutDialog {

    public MMLAboutDialog(JFrame parentFrame) {
        super(parentFrame);
    }

    @Override
    protected JComponent version() {
        JLabel program = new JLabel("MegaMekLab");
        program.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
        JLabel version = new JLabel("Version: " + MMLConstants.VERSION);
        var panel = Box.createVerticalBox();
        panel.add(program);
        panel.add(Box.createVerticalStrut(8));
        panel.add(version);
        return panel;
    }
}
