import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

public class AoOniMIDlet extends MIDlet {
    private GameScreen screen;

    protected void startApp() {
        if (screen == null) {
            screen = new GameScreen(this);
            Display.getDisplay(this).setCurrent(screen);
            screen.start();
        } else {
            screen.resume();
        }
    }

    protected void pauseApp() {
        if (screen != null) {
            screen.pause();
        }
    }

    protected void destroyApp(boolean unconditional) {
        if (screen != null) {
            screen.stop();
        }
    }
}
