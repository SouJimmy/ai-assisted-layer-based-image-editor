package editor.processing;
import static editor.processing.Processing.*;
/** ImageFilter implementations share exactly the same math as normal processing. */
public final class Filters {
    private Filters() {}
    public static ImageFilter grayscale() {return of(Operation.GRAYSCALE);}
    public static ImageFilter sepia() {return of(Operation.SEPIA);}
    public static ImageFilter negative() {return of(Operation.NEGATIVE);}
    private static ImageFilter of(Operation op) {
        return source->new ImageProcessor().process(new Request(source,op,new None())).image();
    }
}
