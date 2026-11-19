import com.jnieves.lector.office.*;
import java.io.*;
import java.nio.file.*;

public class TestMain {
    public static void main(String[] a) throws Exception {
        File in = new File(a[0]);
        File out = new File(a[1]);
        out.mkdirs();
        String n = in.getName().toLowerCase();
        String html;
        if (n.endsWith(".docx")) html = DocxToHtml.convert(in, out);
        else if (n.endsWith(".xlsx")) html = XlsxToHtml.convert(in);
        else html = PptxToHtml.convert(in, out);
        Files.write(new File(out, "body.html").toPath(), html.getBytes("UTF-8"));
        System.out.println("ok " + html.length());
    }
}
