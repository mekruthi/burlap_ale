package edu.brown.cs.burlap.io;

import edu.brown.cs.burlap.screen.ColorPalette;
import edu.brown.cs.burlap.screen.NTSCPalette;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.opencv_core.Mat;

import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.javacpp.opencv_core.CV_8UC3;
import static org.bytedeco.javacpp.opencv_core.addWeighted;
import static org.bytedeco.javacpp.opencv_core.max;

/**
 * A driver for interacting with ALE over the PIPE interface.
 *
 * @author Melrose Roderick.
 */
public class ALEDriver {

    static final String ALE_ERROR_FILE = "ale_err.txt";

    /** File paths for the ale executable and the rom file */
    protected final String alePath;
    protected final String romPath;

    /** The ale process */
    protected Process process;

    /** Data structure holding the edu.brown.cs.burlap.screen image */
    Mat screen;
    /** Data structure holding the raw frame data. If pooling != 0, pool over these frames **/
    Mat frameA;
    Mat frameB;
    PoolingMethod poolingMethod = PoolingMethod.POOLING_METHOD_NONE;
    /** Data structure holding colors */
    ColorPalette colorPalette = new NTSCPalette();
    /** Data structure holding the RAM data */
    protected ConsoleRAM ram;
    /** Data structure holding RL data */
    protected RLData rlData;
    /** Whether termination was requested from the I/O channel */
    protected boolean terminateRequested;

    /** Input object */
    protected BufferedReader in;
    /** Output object */
    protected PrintStream out;

    /** Flags indicating the kind of data we want to receive from ALE */
    protected boolean updateScreen = true;
    protected boolean updateRam = true;
    protected boolean updateRLData = false;
    /** We will request that ALE sends data every 'frameskip' frames. */
    protected int frameskip = 1;

    /** Path of the directory to put the screen recordings */
    protected String recordScreenDir;

    /** The action we send for player B (always noop in this case) */
    protected final int playerBAction = Actions.map("player_b_noop");

    /** A state variable used to track of whether we should receive or send data */
    protected boolean hasObserved;

    protected boolean useRLE = true;

    public ALEDriver(String alePath, String romPath) {
        this.alePath = alePath;
        this.romPath = romPath;
    }

    /** Closes the I/O channel.
     *
     */
    public void close() {
        process.destroy();
    }

    public void setUpdateScreen(boolean updateScreen) {
        this.updateScreen = updateScreen;
    }

    public void setUpdateRam(boolean updateRam) {
        this.updateRam = updateRam;
    }

    public void setUpdateRL(boolean updateRL) {
        this.updateRLData = updateRL;
    }

    public void setFrameskip(int frameskip) {
        this.frameskip = frameskip;
    }

    public void setRecordScreenDir(String recordScreenDir) {
        this.recordScreenDir = recordScreenDir;
    }

    public void setPoolingMethod(PoolingMethod poolingMethod) {
        this.poolingMethod = poolingMethod;
    }

    /**
     *  A blocking method that initialized ALE.
     */
    public void initALE() throws IOException {

        // Start the ale executable
        execALE();

        // Read in the width and height of the edu.brown.cs.burlap.screen
        // Format: <width>-<height>\n
        String line = in.readLine();
        if (line == null) {
            throw new RuntimeException("Error starting ale. Check 'ale_err.txt' for more information");
        }

        String[] tokens = line.split("-");
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);

        // Do some error checking - our width and height should be positive
        if (width <= 0 || height <= 0) {
            throw new RuntimeException("Invalid width/height: "+width+"x"+height);
        }

        // Create the data structures used to store received information
        screen = new Mat(height, width, CV_8UC3);
        frameA = new Mat(height, width, CV_8UC3);
        frameB = new Mat(height, width, CV_8UC3);
        ram = new ConsoleRAM();
        rlData = new RLData();

        // Now send back our preferences
        // Format: <wants-edu.brown.cs.burlap.screen>,<wants-ram>,<frame-skip>,<wants-rldata>\n
        out.printf("%d,%d,%d,%d\n", updateScreen? 1:0, updateRam? 1:0, 1, updateRLData? 1:0);
        out.flush();

        // Initial observe
        observe(null);
    }

    private void execALE() {
        List<String> command = new ArrayList<>();
        command.add(alePath);

        command.add("-game_controller");
        command.add("fifo");

        command.add("-frame_skip");
        command.add("0");

        command.add("-repeat_action_probability");
        command.add("0");

        command.add("-disable_color_averaging");
        command.add("true");

        if (recordScreenDir != null) {
            command.add("-record_screen_dir");
            command.add(recordScreenDir);
        }

        command.add(romPath);

        ProcessBuilder pb = (new ProcessBuilder()).command(command).redirectError(new File(ALE_ERROR_FILE));

        try {
            process = pb.start();

            in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            out = new PrintStream(new BufferedOutputStream(process.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();

            throw new RuntimeException("Failed to start ALE. See 'ale_err.txt' for more info");
        }
    }

    public int getFrameSkip() {
        return frameskip;
    }

    /** Returns the edu.brown.cs.burlap.screen matrix from ALE.
     *
     * @return the matrix for the screen
     */
    public Mat getScreen() {
        return screen;
    }

    /** Returns the RAM from ALE.
     *
     * @return the RAM
     */
    public ConsoleRAM getRAM() {
        return ram;
    }

    public RLData getRLData() {
        return rlData;
    }

    public boolean wantsTerminate() {
        return terminateRequested;
    }

    /** A blocking method which will get the next time step from ALE.
     * @return a boolean...
     */
    public boolean observe(Mat outputFrame) {
        // Ensure that observe() is not called twice, as it will otherwise block
        //  as both ALE and the agent wait for data.
        if (hasObserved) {
            throw new RuntimeException("observe() called without subsequent act().");
        }
        else
            hasObserved = true;

        String line = null;

        // First read in a new line from ALE
        try {
            line = in.readLine();
            if (line == null) return true;
        }
        catch (IOException e) {
            return true;
        }

        // Catch the special keyword 'DIE'
        if (line.equals("DIE")) {
            terminateRequested = true;
            return false;
        }

        // Ignore blank lines (still send an action)
        if (line.length() > 0) {
            // The data format is:
            // <ram-string>:<screen-string>:<rl-data-string>:\n
            //  Some of these elements may be missing, in which case the separating
            //  colons are not sent. For example, if we only want ram and rl data,
            //  the format is <ram>:<rl-data>:

            String[] tokens = line.split(":");

            int tokenIndex = 0;

            // If necessary, first read the RAM data
            if (updateRam) {
                readRam(tokens[tokenIndex++]);
            }

            // Then update the edu.brown.cs.burlap.screen
            if (updateScreen) {
                String screenString = tokens[tokenIndex++];

                if (outputFrame != null) {
                    if (useRLE)
                        readScreenRLE(screenString, outputFrame);
                    else
                        readScreenMatrix(screenString, outputFrame);
                }
            }

            // Finally obtain RL data
            if (updateRLData) {
                readRLData(tokens[tokenIndex++]);
            }
        }

        return false;
    }

    /** After a call to observe(), send back the necessary action.
     *
     * @param action the code of the atari action to perform
     * @return a boolean indicating if ALE has closed.
     */
    public boolean act(int action) {
        return act(action, frameskip);
    }
    public boolean act(int action, int repeat) {
        // Ensure that we called observe() last
        if (!hasObserved) {
            throw new RuntimeException("act() called before observe().");
        } else {
            hasObserved = false;
        }

        boolean err = false;
        if (repeat <= 1) {
            // Swap frameA and B
            Mat frameC = frameA;
            frameA = frameB;
            frameB = frameC;

            sendAction(action);
            err |= observe(frameA);
        } else {
            RLData rlData = new RLData();
            for (int f = 0; f < repeat - 2; f++) {
                err |= actHelper(action, null, rlData);
            }
            err |= actHelper(action, frameB, rlData);
            err |= actHelper(action, frameA, rlData);

            this.rlData = rlData;
        }

        poolFrames();
        return err;
    }

    public boolean reset() {
        // Send reset twice to pool over the frames
        return act(Actions.map("system_reset"), 2);
    }

    private boolean actHelper(int act, Mat frame, RLData rlData) {
        boolean err;

        sendAction(act); hasObserved = false;
        err = observe(frame);
        rlData.reward += this.rlData.reward;
        rlData.isTerminal |= this.rlData.isTerminal;
        rlData.lives = this.rlData.lives;

        return err;
    }

    protected void poolFrames() {
        if (frameB == null) {
            screen = frameA;
            return;
        }
        switch (poolingMethod) {
            case POOLING_METHOD_NONE:
                screen = frameA;
                break;
            case POOLING_METHOD_MAX:
                max(frameA, frameB, screen);
                break;
            case POOLING_METHOD_MEAN:
                addWeighted(frameA, 0.5, frameB, 0.5, 0, screen);
                break;
        }
    }

	/**
     * Helper function to send out an action to ALE
     * @param act the action code to execute
     */
    protected void sendAction(int act) {
        // Send player A's action, as well as the NOOP for player B
        // Format: <player_a_action>,<player_b_action>\n
        out.printf("%d,%d\n", act, 18);
        out.flush();
    }

    /**
     * Read in RL data from a given line.
     * @param line the line of data to parse
     */
    protected void readRLData(String line) {
        // Parse RL data
        // Format: <is-terminal>:<reward>\n
        String[] tokens = line.split(",");

        // Parse the terminal bit
        rlData.isTerminal = (Integer.parseInt(tokens[0]) == 1);
        rlData.reward = Integer.parseInt(tokens[1]);

        // Set number of lives if available.
        if (tokens.length > 2) {
            rlData.lives = Integer.parseInt(tokens[2]);
        }
        // Otherwise, set to -1 for error reporting
        else {
            rlData.lives = -1;
        }
    }

    /**
     * Reads the console RAM from a string
     * @param line The RAM-part of the string sent by ALE.
     */
    protected void readRam(String line) {
        int offset = 0;

        // Read in all of the RAM
        // Format: <r0><r1><r2>...<r127>
        //  where ri is 2 characters representing an integer between 0 and 0xFF
        for (int ptr = 0; ptr < ConsoleRAM.RAM_SIZE; ptr++) {
            int v = Integer.parseInt(line.substring(offset, offset + 2), 16);
            ram.ram[ptr] = v;

            offset += 2;
        }
    }

    /** Reads the edu.brown.cs.burlap.screen matrix update from a string. The string only contains the
     *   pixels that differ from the previous frame.
     *
     * @param line The edu.brown.cs.burlap.screen part of the string sent by ALE.
     */
    protected void readScreenMatrix(String line, Mat frame) {
        BytePointer screenData = frame.data();
        int position = 0;

        byte[] buffer = new byte[frame.rows() * frame.cols() * frame.channels()];

        int ptr = 0;

        // 0.3 protocol - send everything
        for (int y = 0; y < frame.rows(); y++)
            for (int x = 0; x < frame.cols(); x++) {
                int v = byteAt(line, ptr);

                Color c = colorPalette.get(v);
                buffer[position] = (byte)c.getBlue();
                buffer[position + 1] = (byte)c.getGreen();
                buffer[position + 2] = (byte)c.getRed();

                position += 3;

                ptr += 2;
            }

        screenData.put(buffer);
    }

    /** Parses a hex byte in the given String, at position 'ptr'. */
    private int byteAt(String line, int ptr) {
        int ld = line.charAt(ptr+1);
        int hd = line.charAt(ptr);

        if (ld >= 'A') ld -= 'A' - 10;
        else ld -= '0';
        if (hd >= 'A') hd -= 'A' - 10;
        else hd -= '0';

        return (hd << 4) + ld;
    }

	/**
     * Read in a run-length encoded edu.brown.cs.burlap.screen. ALE 0.3-0.4
     * @param line the line of data to read
     */
    protected void readScreenRLE(String line, Mat frame) {

        BytePointer screenData = frame.data();
        int position = 0;

        byte[] buffer = new byte[frame.rows() * frame.cols() * frame.channels()];

        int ptr = 0;

        while (ptr < line.length()) {
            // Read in the next run
            int v = byteAt(line, ptr);
            int l = byteAt(line, ptr + 2);
            ptr += 4;

            Color c = colorPalette.get(v);
            for (int i = 0; i < l; i++) {
                buffer[position] = (byte)c.getBlue();
                buffer[position + 1] = (byte)c.getGreen();
                buffer[position + 2] = (byte)c.getRed();

                position += 3;
            }
        }

        screenData.put(buffer);
    }
}
