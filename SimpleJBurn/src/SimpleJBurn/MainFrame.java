/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package SimpleJBurn;

import gnu.io.CommPortIdentifier;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Iterator;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;
import javax.swing.UIManager;

/**
 *
 * @author mario
 */
public class MainFrame extends javax.swing.JFrame implements PropertyChangeListener {

    static private final String revision = "$Revision: 1.6 $";
    static private final String date = "$Date: 2013/07/31 13:00:00 $";
    //used to end a line in the output window
    static private final String newline = "\n";
    private static final String[] EEPROMTYPES = {"28C64  (8KiB) ", "28C128 (16KiB)", "28C256 (32KiB)", "27SF512 (64KiB)"};
    private static final String[] OFFSETS = {"-----", " 2KiB", " 4KiB", " 8KiB", "16KiB", "24KiB", "32KiB", "48KiB"};
    private ReadTask readTask;
    private WriteTask writeTask;
    MySerial mySerial = new MySerial();
    String selectedComPort;
    byte[] data = new byte[65536];
    byte[] eeprom = new byte[65536];
    int maxAddress = 8192;
    int chipType = 0;
    int offset = 0;
    long filesize = 0;
    int sequenceCtr = 0;
    boolean failure = false;
    
    enum Action {
        DIFF, READ, BLANK
    };

    class ReadTask extends SwingWorker<Void, Void> {

        public int done = 0;
        long start, end = 0;
        int readProgress = 0;
        
        Action action;

        public ReadTask(Action a) {
            this.action = a;
            statusLabel.setText("");
            progressBar.setValue(0);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }

        /*
         * Main task. Executed in background thread.
         */
        @Override
        public Void doInBackground() {
            //check if eeprom should be read or written
            try {
                failure = false;
                //remove old data from input stream to prevent them "poisening" our
                //data
                mySerial.in.skip(mySerial.in.available());
                //take time to read the eeprom
                start = System.currentTimeMillis();
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(mySerial.out));
                String line = "";

                appendToLog("sending command." + newline);
                bw.write("r,0000," + Utility.wordToHex(maxAddress) + ",20" + newline);
                bw.flush();
                appendToLog("command sent." + newline);
                appendToLog("trying to read." + newline);
                int counter = 0, c;
                do {
                    c = mySerial.in.read();
                    if (c == -1) throw new Exception("Communication timeout");
                    eeprom[counter++] = (byte) c;
                    if (counter % 100 == 0) {
                        readProgress = 100 * counter / maxAddress;
                        setProgress(readProgress);
                    }
                } while (counter < maxAddress);
                end = System.currentTimeMillis();
                setProgress(100);


            } catch (Exception e) {
                appendToLog("Error: " + e.getMessage() + newline);
                failure = true;
            }

            /**
             * Random random = new Random(); int progress = 0; int steps = 100 /
             * (maxAddress / 1024); //Initialize progress property.
             * setProgress(0); for (int i = offset; i < maxAddress; i += 1024) {
             * try { Thread.sleep(random.nextInt(300)); } catch
             * (InterruptedException ignore) { } progress = i * 100 /
             * maxAddress; done = i; setProgress(Math.min(progress, 100)); }
             * setProgress(100); return null; *
             */
            return null;
        }

        /*
         * Executed in event dispatching thread
         */
        @Override
        public void done() {
            Toolkit.getDefaultToolkit().beep();
            verifyButton.setEnabled(true);
            blankButton.setEnabled(true);
            readButton.setEnabled(true);
            setCursor(null); //turn off the wait cursor
            appendToLog(maxAddress + " bytes read in " + (float) (end - start) / 1000
                    + " seconds " + newline);
            if (this.action == Action.DIFF) {
                appendToLog("Checking difference between loaded ROM file and data on EEPROM"
                        + newline);
                int byteCount = 0;
                //this.readEEPROM();
                for (int i = 0; i < filesize; i++) {
                    if (data[i] != eeprom[i + offset]) {
                        byteCount++;
                    }
                }
                appendToLog(filesize + " bytes checked from 0x" + Utility.wordToHex(offset)
                        + " to 0x" + Utility.wordToHex(offset + (int) filesize - 1) + ", " + byteCount
                        + " byte are different." + newline);
                if (!failure && byteCount == 0)
                    failure = false;
                else
                    failure = true;
            } else if (this.action == Action.BLANK) {
                int i, byteCount = 0;
                //this.readEEPROM();
                for (i = 0; i < maxAddress; i++) {
                    if (eeprom[i] != -1)
                        break;
                }
                if (i == maxAddress) {
                    appendToLog( "Blank check success! " + newline );
                    if (!failure) failure = false;
                } else {
                    appendToLog( "Blank check failed! " + newline );
                    failure = true;
                }
            }
            CheckSequence();
        }
    }

    class WriteTask extends SwingWorker<Void, Void> {

        public int done = 0;
        int len;
        int address;
        long start, end = 0;
        int writeProgress = 0;

        public WriteTask(int a, int l) {
            this.len = l;
            this.address = a;
            statusLabel.setText("");
            progressBar.setValue(0);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
        /*
         * Main task. Executed in background thread.
         */

        @Override
        public Void doInBackground() {
            try {
                int c;
                failure = false;
                //take time to read the eeprom
                start = System.currentTimeMillis();
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(mySerial.out));
                String line = "";
                appendToLog("sending command." + newline);

                bw.write("P" + newline);
                bw.flush();

                for (int i = 0; i < len; i += 1024) {
                    bw.write("w," + Utility.wordToHex(address + i) + "," + Utility.wordToHex(1024) + newline);
                    bw.flush();
                    writeProgress = i * 100 / len;
                    setProgress(writeProgress);
                    mySerial.out.write(data, i, 1024);
                    appendToLog("wrote data from 0x" + Utility.wordToHex(address + i)
                            + " to 0x" + Utility.wordToHex(address + i + 1023) + newline);
                    do {
                        c = mySerial.in.read();
                        if (c == -1) throw new Exception("Communication timeout");
                    } while (c != '%');

                }

                bw.write("p" + newline);
                bw.flush();

                end = System.currentTimeMillis();
                setProgress(100);

            } catch (Exception e) {
                appendToLog("Error: " + e.getMessage() + newline);
                failure = true;
            }

            /**
             * Random random = new Random(); int progress = 0; int steps = 100 /
             * (maxAddress / 1024); //Initialize progress property.
             * setProgress(0); for (int i = offset; i < maxAddress; i += 1024) {
             * try { Thread.sleep(random.nextInt(300)); } catch
             * (InterruptedException ignore) { } progress = i * 100 /
             * maxAddress; done = i; setProgress(Math.min(progress, 100)); }
             * setProgress(100); return null; *
             */
            return null;
            
        }

        /*
         * Executed in event dispatching thread
         */
        @Override
        public void done() {
            Toolkit.getDefaultToolkit().beep();
            eraseButton.setEnabled(true);
            writeButton.setEnabled(true);
            setCursor(null); //turn off the wait cursor
            appendToLog("data sent." + newline);

            appendToLog("wrote " + len + " bytes from 0x"
                    + Utility.wordToHex(address) + " to 0x"
                    + Utility.wordToHex(address + (int) len - 1) + " in "
                    + (float) (end - start) / 1000
                    + " seconds " + newline);
            if (!failure) failure = false;
            CheckSequence();
        }
    }

    /**
     * Invoked when task's progress property changes.
     */
    public void propertyChange(PropertyChangeEvent evt) {
        if ("progress" == evt.getPropertyName()) {
            int progress = (Integer) evt.getNewValue();
            progressBar.setValue(progress);
            //log.replaceSelection(readTask.getProgress() + "% completed, " + task.done + " bytes written " + newline,null);
        }
    }

    /**
     * Creates new form MainFrame
     */
    public MainFrame() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            System.err.println("Error setting native LAF: " + e);
        }
        
        initComponents();

        //Create a file chooser
        fc = new JFileChooser();
        fc.setPreferredSize(new Dimension(500, 600));

        StyleContext sc = StyleContext.getDefaultStyleContext();
        AttributeSet aset = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, Color.BLACK);

        aset = sc.addAttribute(aset, StyleConstants.FontFamily, "Lucida Console");
        aset = sc.addAttribute(aset, StyleConstants.Alignment, StyleConstants.ALIGN_JUSTIFIED);

        textPane.setCharacterAttributes(aset, false);
    }

    private void CheckSequence() {
        if (failure == true && sequenceCtr != 0) sequenceCtr = 1;
        
        switch (sequenceCtr) {
            case 5:
                sequenceCtr --;
                if (eraseCheckBox.isSelected()) {
                    eraseButtonActionPerformed(null);
                    break;
                }
            case 4:
                sequenceCtr --;
                if (blankCheckBox.isSelected()) {
                    blankButtonActionPerformed(null);
                    break;
                }
            case 3:
                sequenceCtr --;
                if (writeCheckBox.isSelected()) {
                    writeButtonActionPerformed(null);
                    break;
                }
            case 2:
                sequenceCtr --;
                if (verifyCheckBox.isSelected()) {
                    verifyButtonActionPerformed(null);
                    break;
                }
            case 1:
                sequenceCtr --;
                sequenceButton.setEnabled(true);
            default:
                if (failure == true) {
                    statusLabel.setText("Failure!");
                    statusLabel.setForeground(Color.red);
                } else {
                    statusLabel.setText("Success!");
                    statusLabel.setForeground(Color.decode("#00C000"));
                }
//                statusLabel.setText("");
                break;
        }
    }            

    private void CheckConnected() {
        if (mySerial.isConnected() == false) {
            serialSelectActionPerformed(null);
        }
    }
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanel1 = new javax.swing.JPanel();
        progressBar = new javax.swing.JProgressBar();
        writeButton = new javax.swing.JButton();
        serialSelect = new javax.swing.JComboBox();
        readButton = new javax.swing.JButton();
        eraseButton = new javax.swing.JButton();
        loadButton = new javax.swing.JButton();
        saveButton = new javax.swing.JButton();
        versionButton = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        eepromTypeSelect = new javax.swing.JComboBox();
        jLabel3 = new javax.swing.JLabel();
        offsetSelect = new javax.swing.JComboBox();
        showImageButton = new javax.swing.JButton();
        showDataButton = new javax.swing.JButton();
        verifyButton = new javax.swing.JButton();
        sequenceButton = new javax.swing.JButton();
        eraseCheckBox = new javax.swing.JCheckBox();
        blankCheckBox = new javax.swing.JCheckBox();
        writeCheckBox = new javax.swing.JCheckBox();
        verifyCheckBox = new javax.swing.JCheckBox();
        statusLabel = new javax.swing.JLabel();
        blankButton = new javax.swing.JButton();
        jScrollPane2 = new javax.swing.JScrollPane();
        textPane = new javax.swing.JTextPane();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Simple JBurn 2");
        setBounds(new java.awt.Rectangle(0, 0, 0, 0));
        setMinimumSize(new java.awt.Dimension(800, 600));

        progressBar.setToolTipText("Progress indicator");
        progressBar.setFocusable(false);
        progressBar.setStringPainted(true);

        writeButton.setText("Write");
        writeButton.setToolTipText("Write to chip");
        writeButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                writeButtonActionPerformed(evt);
            }
        });

        HashSet h = mySerial.getAvailableSerialPorts();

        Iterator<CommPortIdentifier> thePorts = h.iterator();
        while (thePorts.hasNext()) {
            serialSelect.addItem(thePorts.next().getName());
        }

        serialSelect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serialSelectActionPerformed(evt);
            }
        });

        readButton.setText("Read");
        readButton.setToolTipText("Read from chip");
        readButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                readButtonActionPerformed(evt);
            }
        });

        eraseButton.setText("Erase");
        eraseButton.setToolTipText("Erase chip");
        eraseButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                eraseButtonActionPerformed(evt);
            }
        });

        loadButton.setText("Load Image");
        loadButton.setToolTipText("Load image from disk");
        loadButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                loadButtonActionPerformed(evt);
            }
        });

        saveButton.setText("Save Image");
        saveButton.setToolTipText("Save image to disk");
        saveButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveButtonActionPerformed(evt);
            }
        });

        versionButton.setText("Version");
        versionButton.setToolTipText("Programmer versioning");
        versionButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                versionButtonActionPerformed(evt);
            }
        });

        jLabel1.setText("Serial :");

        jLabel2.setText("EEPROM Type :");

        eepromTypeSelect.setModel(new javax.swing.DefaultComboBoxModel(EEPROMTYPES));
        eepromTypeSelect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                eepromTypeSelectActionPerformed(evt);
            }
        });

        jLabel3.setText("Offset :");

        offsetSelect.setModel(new javax.swing.DefaultComboBoxModel(OFFSETS));
        offsetSelect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                offsetSelectActionPerformed(evt);
            }
        });

        showImageButton.setText("Show Image");
        showImageButton.setToolTipText("Display image buffer on screen");
        showImageButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showImageButtonActionPerformed(evt);
            }
        });

        showDataButton.setText("Show Data");
        showDataButton.setToolTipText("Display chip buffer on screen");
        showDataButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showDataButtonActionPerformed(evt);
            }
        });

        verifyButton.setText("Verify");
        verifyButton.setToolTipText("Verify chip matches loaded image");
        verifyButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                verifyButtonActionPerformed(evt);
            }
        });

        sequenceButton.setText("Run Sequence");
        sequenceButton.setToolTipText("Perform every box checked in sequence");
        sequenceButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sequenceButtonActionPerformed(evt);
            }
        });

        eraseCheckBox.setSelected(true);
        eraseCheckBox.setText("Erase");

        blankCheckBox.setSelected(true);
        blankCheckBox.setText("Blank Chk");

        writeCheckBox.setSelected(true);
        writeCheckBox.setText("Write");

        verifyCheckBox.setSelected(true);
        verifyCheckBox.setText("Verify");

        statusLabel.setFont(new java.awt.Font("Ubuntu", 0, 18)); // NOI18N
        statusLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);

        blankButton.setText("Blank Chk");
        blankButton.setToolTipText("Check if chip is blank");
        blankButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                blankButtonActionPerformed(evt);
            }
        });

        org.jdesktop.layout.GroupLayout jPanel1Layout = new org.jdesktop.layout.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(sequenceButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(eraseCheckBox, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(loadButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 81, Short.MAX_VALUE)
                                .add(18, 18, 18)
                                .add(saveButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 80, Short.MAX_VALUE)
                                .add(17, 17, 17)))
                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING)
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(jLabel3)
                                .add(18, 18, 18)
                                .add(offsetSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 74, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(jLabel2)
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(eepromTypeSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 135, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(6, 6, 6)
                                .add(blankCheckBox, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(writeCheckBox, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(verifyCheckBox, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(eraseButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                        .add(blankButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                        .add(readButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                        .add(writeButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                        .add(verifyButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .add(31, 31, 31)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING)
                    .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                        .add(org.jdesktop.layout.GroupLayout.TRAILING, jPanel1Layout.createSequentialGroup()
                            .add(versionButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 75, Short.MAX_VALUE)
                            .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                            .add(showImageButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 98, Short.MAX_VALUE)
                            .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                            .add(showDataButton, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 92, Short.MAX_VALUE))
                        .add(jPanel1Layout.createSequentialGroup()
                            .add(statusLabel)
                            .add(0, 0, Short.MAX_VALUE))
                        .add(progressBar, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(jLabel1)
                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                        .add(serialSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 179, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                        .add(versionButton)
                        .add(showImageButton)
                        .add(showDataButton))
                    .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                        .add(loadButton)
                        .add(saveButton)
                        .add(offsetSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                        .add(jLabel3)))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                        .add(serialSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                        .add(jLabel1))
                    .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                        .add(eepromTypeSelect, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                        .add(jLabel2)))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(eraseButton)
                    .add(blankButton)
                    .add(readButton)
                    .add(writeButton)
                    .add(verifyButton)
                    .add(progressBar, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(sequenceButton)
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(3, 3, 3)
                        .add(statusLabel))
                    .add(eraseCheckBox)
                    .add(blankCheckBox)
                    .add(writeCheckBox)
                    .add(verifyCheckBox))
                .addContainerGap())
        );

        textPane.setEditable(false);
        textPane.setFont(new java.awt.Font("Monospaced", 0, 12)); // NOI18N
        textPane.setFocusable(false);
        jScrollPane2.setViewportView(textPane);
        log = textPane.getStyledDocument();

        org.jdesktop.layout.GroupLayout layout = new org.jdesktop.layout.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jScrollPane2)
            .add(org.jdesktop.layout.GroupLayout.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jScrollPane2, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 422, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void blankButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_blankButtonActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_blankButtonActionPerformed

    private void sequenceButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sequenceButtonActionPerformed
        sequenceButton.setEnabled(false);
        sequenceCtr = 5;
        failure = false;
        CheckSequence();
    }//GEN-LAST:event_sequenceButtonActionPerformed

    private void verifyButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_verifyButtonActionPerformed
        verifyButton.setEnabled(false);

        CheckConnected();

        //Instances of javax.swing.SwingWorker are not reusuable, so
        //we create new instances as needed.
        readTask = new ReadTask(Action.DIFF);
        readTask.addPropertyChangeListener(this);
        readTask.execute();
    }//GEN-LAST:event_verifyButtonActionPerformed

    private void showDataButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showDataButtonActionPerformed
        String line = "";
        for (int i = 0; i < maxAddress; i++) {
            if (i % 32 == 0) {
                line = line + "0x" + Utility.wordToHex(i) + "  ";
            }
            line = line + Utility.byteToHex(eeprom[i]) + " ";
            if (i % 32 == 31) {
                appendToLog(line + newline);
                line = "";
            }

        }
    }//GEN-LAST:event_showDataButtonActionPerformed

    private void showImageButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showImageButtonActionPerformed
        String line = "";
        for (int i = 0; i < maxAddress; i++) {
            if (i % 32 == 0) {
                line = line + "0x" + Utility.wordToHex(i) + "  ";
            }
            line = line + Utility.byteToHex(data[i]) + " ";
            if (i % 32 == 31) {
                appendToLog( line + newline);
                line = "";
            }

        }
    }//GEN-LAST:event_showImageButtonActionPerformed

    private void offsetSelectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_offsetSelectActionPerformed
        if (offsetSelect.getSelectedIndex() == 0)
        offset = 0;
        else
        offset =  Integer.parseInt(offsetSelect.getSelectedItem().toString().replaceAll("[\\D]", "")) * 1024;

        appendToLog("Offset is now set to : " + offsetSelect.getSelectedItem() + newline);
        appendToLog("data will be written from 0x" + Utility.wordToHex(offset) + newline);

        if (offset + filesize > maxAddress) {
            JOptionPane.showMessageDialog(this, "The offset you choose will cause the current file not to fit in the choosen EEPROM anymore", "Warning", JOptionPane.WARNING_MESSAGE);
            textPane.setForeground(Color.red);
            appendToLog("WARNING!! The offset you choose will cause the current file not to fit in the choosen EEPROM anymore " + newline);
            textPane.setForeground(Color.black);
        }
    }//GEN-LAST:event_offsetSelectActionPerformed

    private void eepromTypeSelectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_eepromTypeSelectActionPerformed
        switch (eepromTypeSelect.getSelectedIndex()) {
            case 0:
            maxAddress = 8192;
            chipType = 0;
            break;
            case 1:
            maxAddress = 16384;
            chipType = 2;
            break;
            case 2:
            maxAddress = 32768;
            chipType = 2;
            break;
            case 3:
            maxAddress = 65536;
            chipType = 3;
            break;
            default:
            maxAddress = 8192;
            chipType = 0;
            break;
        }

        CheckConnected();

        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(mySerial.out));
            bw.write("C," + Utility.wordToHex(chipType) + newline);
            bw.flush();
        } catch (Exception e) {
            appendToLog("Error: " + e.getMessage() + newline);
        }

        appendToLog( "now selected: " + eepromTypeSelect.getSelectedItem()
            + ", address range = 0x0000 to 0x"
            + Utility.wordToHex(maxAddress - 1) + newline);
    }//GEN-LAST:event_eepromTypeSelectActionPerformed

    private void versionButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_versionButtonActionPerformed
        appendToLog("Simple JBurn - Revision : " + revision + ", " + date + newline);
        if (mySerial.isConnected()) {
            try {
                mySerial.out.write('V');
                mySerial.out.write('\n');
                String line = "";
                int c;
                do {
                    c = mySerial.in.read();
                    if (c == -1) throw new Exception("Communication timeout");
                    line = line + (char) c;
                    if (c == '\n') {
                        appendToLog( line);
                        line = "";
                    }
                } while (c != '\n');
            } catch (Exception e) {
                appendToLog("Error: " + e.getMessage() + newline);
            }
        } else {
            appendToLog("Error: Not connected to any Programmer!" + newline);
        }
    }//GEN-LAST:event_versionButtonActionPerformed

    private void saveButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveButtonActionPerformed
        int returnVal = fc.showSaveDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            //This is where a real application would open the file.
            appendToLog("Saving: " + file.getAbsolutePath() + "."
                + newline);
            try {
                FileOutputStream fout = new FileOutputStream(file.getAbsolutePath());
                fout.write(eeprom, 0, maxAddress);
                appendToLog( maxAddress + " bytes saved to \"" + file.getName() + "\"" + newline);
            } catch (IOException e) {
                appendToLog( "Error while saving file");
            }
        } else {
            appendToLog( "Save command cancelled by user." + newline);
        }
    }//GEN-LAST:event_saveButtonActionPerformed

    private void loadButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_loadButtonActionPerformed
        int returnVal = fc.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            //This is where a real application would open the file.
            appendToLog( "Opening: " + file.getAbsolutePath() + "."
                + newline);
            if (file.length() <= 65536) {
                loadFile(file);
            } else {
                appendToLog( "Error: " + file.getName()
                    + " is too big to load.");
            }
        } else {
            appendToLog( "Open command cancelled by user." + newline);
        }
    }//GEN-LAST:event_loadButtonActionPerformed

    private void eraseButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_eraseButtonActionPerformed
        eraseButton.setEnabled(false);

        CheckConnected();

        statusLabel.setText("");
        progressBar.setValue(0);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        appendToLog("Erasing EEPROM." + newline);
        try {
            int c;
            mySerial.out.write('E');
            mySerial.out.write('\n');
            do {
                c = mySerial.in.read();
                if (c == -1) throw new Exception("Communication timeout");
            } while (c != '%');

            failure = false;
            appendToLog("Completed." + newline);
        } catch (Exception e) {
            appendToLog("Error: " + e.getMessage() + newline);
            failure = true;
        }
        eraseButton.setEnabled(true);
        setCursor(null); //turn off the wait cursor
        CheckSequence();
    }//GEN-LAST:event_eraseButtonActionPerformed

    private void readButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_readButtonActionPerformed
        readButton.setEnabled(false);

        CheckConnected();

        //Instances of javax.swing.SwingWorker are not reusuable, so
        //we create new instances as needed.
        readTask = new ReadTask(Action.READ);
        readTask.addPropertyChangeListener(this);
        readTask.execute();
    }//GEN-LAST:event_readButtonActionPerformed

    private void serialSelectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serialSelectActionPerformed
        if (serialSelect.getSelectedItem() == null) return;

        appendToLog("now selected: " + serialSelect.getSelectedItem() + newline);
        selectedComPort = (String) serialSelect.getSelectedItem();
        try {
            mySerial.disconnect();
            mySerial.connect(selectedComPort, 460800);
            appendToLog(selectedComPort + " is now connected." + newline);

            // Wait for Arduino to connect
            Thread.sleep(2000);
        } catch (Exception ex) {
            appendToLog( "Error : " + ex.getMessage() + newline);
        }
    }//GEN-LAST:event_serialSelectActionPerformed

    private void writeButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_writeButtonActionPerformed
        int size;

        if (maxAddress > filesize) size = (int)filesize;
        else size = maxAddress;

        if (maxAddress < size + offset) size = maxAddress - offset;

        writeButton.setEnabled(false);

        CheckConnected();

        //Instances of javax.swing.SwingWorker are not reusuable, so
        //we create new instances as needed.

        writeTask = new WriteTask(offset, size);
        writeTask.addPropertyChangeListener(this);
        writeTask.execute();
    }//GEN-LAST:event_writeButtonActionPerformed

    private void appendToLog(String text) {
        int len = textPane.getDocument().getLength();
        textPane.setCaretPosition(len);
        try {
            log.insertString(log.getLength(),text,null);
        } catch (BadLocationException e) {
            System.err.println("Output Error" + e.getMessage() + newline);   
        }
    }
    
    public void loadFile(File file) {
            try {
                FileInputStream fin = new FileInputStream(file.getAbsolutePath());
                filesize = file.length();
                for (int i = 0; i < file.length(); i++) {
                    data[i] = (byte) fin.read();
                }
                appendToLog( filesize + " bytes loaded from \"" + file.getName() + "\"" + newline);
            } catch (IOException e) {
                appendToLog( "Error: File not found");
            }
    }
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton blankButton;
    private javax.swing.JCheckBox blankCheckBox;
    private javax.swing.JComboBox eepromTypeSelect;
    private javax.swing.JButton eraseButton;
    private javax.swing.JCheckBox eraseCheckBox;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JButton loadButton;
    private javax.swing.JComboBox offsetSelect;
    private javax.swing.JProgressBar progressBar;
    private javax.swing.JButton readButton;
    private javax.swing.JButton saveButton;
    private javax.swing.JButton sequenceButton;
    private javax.swing.JComboBox serialSelect;
    private javax.swing.JButton showDataButton;
    private javax.swing.JButton showImageButton;
    private javax.swing.JLabel statusLabel;
    public javax.swing.JTextPane textPane;
    private javax.swing.JButton verifyButton;
    private javax.swing.JCheckBox verifyCheckBox;
    private javax.swing.JButton versionButton;
    private javax.swing.JButton writeButton;
    private javax.swing.JCheckBox writeCheckBox;
    // End of variables declaration//GEN-END:variables
    private JFileChooser fc;
    private StyledDocument log;
}
