import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.AudioDeviceFactory;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private String[][] musicas = {};
    private ArrayList<Song> msc_lista = new ArrayList<Song>();
    private ArrayList<String[]> t_mscs = new ArrayList<String[]>();
    private Song msc_atual;
    private Song msc_t;
    private Song msc_removida;
    private SwingWorker runner;

    private boolean press_playpause = true;
    private boolean ativ_playpause = true;
    private boolean ativ_parar = false;
    private boolean paused = false;
    private boolean isPlaying = false;
    private volatile boolean stopRequested = false;

    private PlayerWindow window;
    private int index;
    private int currentFrame = 0;
    private Thread playThread = new Thread();

    private final ActionListener buttonListenerPlayNow = e ->{
        currentFrame = 0;
        index = window.getIndex();
        msc_atual = msc_lista.get(index);
        press_playpause = true;

        runner = new SwingWorker() {
            @Override
            public Object doInBackground() throws Exception {
                // Setando as informações na tela
                window.setPlayingSongInfo(msc_atual.getTitle(), msc_atual.getAlbum(), msc_atual.getArtist());

                // Fechando o bitstream e o device, se estiverem abertos
                closeStreams();

                // Abrindo o device e o bitstream da música atual
                try {
                    device = FactoryRegistry.systemRegistry().createAudioDevice();
                    device.open(decoder = new Decoder());
                    bitstream = new Bitstream(msc_atual.getBufferedInputStream());
                } catch (JavaLayerException | FileNotFoundException ex) {
                    throw new RuntimeException(ex);
                }

                // Reproduzindo a música enquanto o botão de play/pause estiver ativado
                while (press_playpause) {
                    try {
                        window.setTime((int) (currentFrame * (int) msc_atual.getMsPerFrame()), (int) msc_atual.getMsLength());
                        window.setPlayPauseButtonIcon(1);
                        window.setEnabledPlayPauseButton(true);
                        window.setEnabledStopButton(true);
                        ativ_playpause = true;
                        ativ_parar = true;

                        playNextFrame();

                    } catch (JavaLayerException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                return null;
            }
        };

        runner.execute();

    };
    private final ActionListener buttonListenerRemove = e ->{
        // selecionando a música e removendo da lista
        int index = window.getIndex();
        msc_removida = msc_lista.get(index);
        msc_lista.remove(index);
        musicas = removeM(musicas, index);
        this.window.setQueueList(musicas);

        // musica reproduzida é comparada com a removida
        if (msc_atual.equals(msc_removida)) {
            stopPlaying(); // se elas forem iguais, a reprodução é interrompida
        }

    };
    private final ActionListener buttonListenerAddSong = e ->{
        try {
            Song music = this.window.openFileChooser();
            msc_lista.add(music);
            String[] info = music.getDisplayInfo();
            int tam = musicas.length;
            musicas = Arrays.copyOf(musicas, musicas.length+1);
            musicas[tam] = info;
            this.window.setQueueList(musicas);
        }catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException ex) {
            throw new RuntimeException(ex);
        }
    };
    private final ActionListener buttonListenerPlayPause = e -> {
        if (isPlaying) {
            // se a música estiver tocando, pausa a reprodução
            press_playpause = false;
            ativ_playpause = false;
            isPlaying = false; // atualiza o estado de reprodução da música
            window.setPlayPauseButtonIcon(0);
        } else {
            // se a música estiver pausada, retoma a reprodução
            press_playpause = true;
            ativ_playpause = true;
            isPlaying = true; // atualiza o estado de reprodução da música
            window.setPlayPauseButtonIcon(1);
        }
    };
    private final ActionListener buttonListenerStop = e ->{
        stopPlaying();
    };
    private final ActionListener buttonListenerNext = e ->{};
    private final ActionListener buttonListenerPrevious = e ->{};
    private final ActionListener buttonListenerShuffle = e ->{};
    private final ActionListener buttonListenerLoop = e ->{};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    private String[][] removeM(String[][] list, int index){
        String[][] list2 = new String[list.length-1][];
        System.arraycopy(list, 0, list2, 0, index);
        System.arraycopy(list, index+1, list2, index, list.length-index-1);
        return list2;
    }
    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "Spotify2",
                musicas,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO: Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO: Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO: Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }
    private void closeStreams() {
        if (bitstream != null) {
            try {
                bitstream.close();
            } catch (BitstreamException ex) {
                throw new RuntimeException(ex);
            }
            device.close();
        }
    }
    private void stopPlaying() {
        // Solicita a interrupção da thread
        stopRequested = true;

        // Deixa o botão com ícone de play
        window.setEnabledPlayPauseButton(false);
        window.setPlayPauseButtonIcon(0);

        // Desabilita o stop
        window.setEnabledStopButton(false);

        // Deixa a aba de músicas em branco
        window.resetMiniPlayer();

        try {
            // Fecha o device e a bitstream
            if (bitstream != null){
                bitstream.close();
            }
            if (device != null) {
                device.close();
            }
        } catch (BitstreamException bitstreamException) {
            bitstreamException.printStackTrace();
        } finally {
            // Interrompe a thread se ainda estiver em execução
            if (playThread != null && playThread.isAlive()) {
                playThread.interrupt();
            }
        }
    }
    //</editor-fold>
}