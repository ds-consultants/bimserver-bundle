package eu.dsconsultants.bimserver.bimserverbundle.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.bimserver.plugins.renderengine.RenderEngineException;
import org.bimserver.shared.exceptions.PluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import org.ifcopenshell.IfcGeomServerClientEntity;

public class DsIfcGeomServerClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DsIfcGeomServerClient.class);
    private static final String IFCOPENSHELL_BUILDS_DESCRIPTOR
            = "https://github.com/ds-consultants/IfcOpenShell/releases/download/v0.6.0-5526f42/v0.6.0.json";

    private Process process = null;
    private LittleEndianDataInputStream dis = null;
    private LittleEndianDataOutputStream dos = null;
    private boolean hasMore = false;

    private volatile boolean running = true;

    private String executableFilename;

    public String getExecutableFilename() {
        return executableFilename;
    }

    @Override
    public void close() throws RenderEngineException {
        running = false;
        terminate();
    }

    private static String getOs() throws PluginException {
        final String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            return "win";
        } else if (os.contains("osx") || os.contains("os x") || os.contains("darwin") || os.contains("mac")) {
            return "osx";
        } else if (os.contains("linux")) {
            return "linux";
        } else {
            throw new PluginException(String.format("IfcOpenShell is not available on the %s platorm", os));
        }
    }

    private static String getExecutableExtension() {
        final String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            return ".exe";
        } else {
            return "";
        }
    }

    public static Path getExecutablePathFromRepo(Path root) throws PluginException {
        final String executableName = "IfcGeomServer" + getExecutableExtension();
        final String operatingSystem = getOs();
        final String bitness = operatingSystem.equals("osx") ? "64" : System.getProperty("sun.arch.data.model");

        return root.resolve("exe")
                .resolve(bitness)
                .resolve(operatingSystem)
                .resolve(executableName);
    }

    private Path getSourcePath() throws RenderEngineException {
        String name = getClass().getName();
        int lastDot = name.lastIndexOf(".");
        name = name.substring(lastDot + 1) + ".class";
        try {
            return Paths.get(this.getClass().getResource(name).toURI())
                    .getParent().getParent().getParent().getParent().getParent();
        } catch (URISyntaxException e) {
            throw new RenderEngineException(e);
        }
    }

    public enum ExecutableSource {
        REPOSITORY,
        GITHUB_RELEASE
    }

    public DsIfcGeomServerClient(ExecutableSource source) throws RenderEngineException {
        getExecutable(source, Paths.get(System.getProperty("user.home")));
    }

    public DsIfcGeomServerClient(ExecutableSource source, Path homeDir) throws RenderEngineException {
        getExecutable(source, homeDir);
    }

    private void getExecutable(ExecutableSource source, Path homeDir) throws RenderEngineException {
        if (source == ExecutableSource.REPOSITORY) {
            try {
                initialize(getExecutablePathFromRepo(getSourcePath()).toString());
            } catch (PluginException e) {
                throw new RenderEngineException(e);
            }
        } else if (source == ExecutableSource.GITHUB_RELEASE) {
            boolean initialized = false;
            String platform = "unknown";

            try {
                URL buildsUrl = new URL(IFCOPENSHELL_BUILDS_DESCRIPTOR);
                JsonArray builds = new Gson().fromJson(new InputStreamReader(buildsUrl.openStream()), JsonArray.class);

                String os = getOs();

                if ("osx".equals(os)) {
                    platform = "macOS 64";
                } else {
                    platform = Character.toUpperCase(os.charAt(0)) + os.substring(1) + " " + System.getProperty("sun.arch.data.model");
                }

                for (int i = 0; i < builds.size(); i++) {
                    String platformValue = builds.get(i).getAsJsonObject().get("platform").getAsString();
                    String productValue = builds.get(i).getAsJsonObject().get("product").getAsString();
                    String urlValue = builds.get(i).getAsJsonObject().get("url").getAsString();

                    if ("IfcGeomServer".equals(productValue) && platform.equals(platformValue)) {
                        String baseName = new File(new URL(urlValue).getPath()).getName();
                        baseName = baseName.substring(0, baseName.length() - 4);
                        baseName += getExecutableExtension();
                        File exePath = homeDir.resolve(baseName).toFile();

                        if (!exePath.exists()) {
                            LOGGER.info(String.format("Downloading from %s", urlValue));

                            File tempZip = File.createTempFile(baseName, ".zip");

                            FileUtils.copyInputStreamToFile(new URL(urlValue).openStream(), tempZip);

                            // tfk: assume single entry
                            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
                                // tfk: assume single entry
                                zis.getNextEntry();

                                exePath.getParentFile().mkdirs();
                                LOGGER.info(String.format("Unzipping to %s", exePath.toString()));

                                int len;
                                byte[] buffer = new byte[1024];
                                try (FileOutputStream fos = new FileOutputStream(exePath)) {
                                    while ((len = zis.read(buffer)) > 0) {
                                        fos.write(buffer, 0, len);
                                    }
                                }

                                zis.closeEntry();
                            }

                            tempZip.delete();

                            try {
                                Files.setPosixFilePermissions(exePath.toPath(), Collections.singleton(PosixFilePermission.OWNER_EXECUTE));
                            } catch (IOException | UnsupportedOperationException e) {
                                LOGGER.trace("Exception during setting Posix permissions", e);
                            }
                        }

                        initialize(exePath.toString());
                        initialized = true;
                        break;
                    }
                }
            } catch (JsonSyntaxException | JsonIOException | IOException | PluginException e) {
                throw new RenderEngineException(e);
            }
            if (!initialized) {
                throw new RenderEngineException("No IfcGeomServer executable found for platform '" + platform + "'");
            }
        }
    }

    public DsIfcGeomServerClient(String executableFilename) throws RenderEngineException {
        initialize(executableFilename);
    }

    private void initialize(String executableFilename) throws RenderEngineException {
        try {
            this.executableFilename = executableFilename;

            process = Runtime.getRuntime().exec(executableFilename);
            dos = new LittleEndianDataOutputStream(process.getOutputStream());
            dis = new LittleEndianDataInputStream(process.getInputStream());

            if (dis.readInt() != HELLO) {
                LOGGER.error("Invalid welcome message received");
                terminate();
                return;
            }
            Hello h = new Hello();
            h.read(dis);

            String reportedVersion = h.getString();
            if (!VERSION.equals(reportedVersion)) {
                terminate();
                throw new RenderEngineException(String.format("Version mismatch: Plugin version %s does not match IfcOpenShell version %s", VERSION, reportedVersion));
            }
        } catch (IOException e) {
            throw new RenderEngineException(e);
        }
    }

    public void loadModel(InputStream inputStream) throws RenderEngineException {
        IfcModel m = new IfcModel(inputStream);
        try {
            m.write(dos);
            askForMore();
        } catch (IOException e) {
            close();
        }
    }

    public void loadModel(InputStream inputStream, long length) throws RenderEngineException {
        IfcModel m = new IfcModel(inputStream, length);
        try {
            m.write(dos);
            askForMore();
        } catch (IOException e) {
            close();
        }
    }

    private static final int HELLO = 0xff00;
    private static final int IFC_MODEL = HELLO + 1;
    private static final int GET = IFC_MODEL + 1;
    private static final int ENTITY = GET + 1;
    private static final int MORE = ENTITY + 1;
    private static final int NEXT = MORE + 1;
    private static final int BYE = NEXT + 1;
    private static final int GET_LOG = BYE + 1;
    private static final int LOG = GET_LOG + 1;
    private static final int DEFLECTION = LOG + 1;
    private static final int SETTING = DEFLECTION + 1;

    private static String VERSION = "IfcOpenShell-0.6.0a1-0";

    abstract static class Command {

        abstract void read_contents(LittleEndianDataInputStream s) throws IOException;

        abstract void write_contents(LittleEndianDataOutputStream s) throws IOException;

        int iden;
        int len;

        void read(LittleEndianDataInputStream s) throws IOException {
            len = s.readInt();
            read_contents(s);
        }

        void write(LittleEndianDataOutputStream s) throws IOException {
            s.writeInt(iden);
            ByteArrayOutputStream oss = new ByteArrayOutputStream();
            write_contents(new LittleEndianDataOutputStream(oss));

            // Comment Ruben: It seems redundant to send the size twice (when sending a String, LittleEndianness should not change the size I think)
            // Also storing the intermediate results in another buffer can be avoided I think, why not send the original s variable to write_contents?
            s.writeInt(oss.size());
            s.write(oss.toByteArray());
            s.flush();
        }

        Command(int iden) {
            this.iden = iden;
        }

        protected String readString(LittleEndianDataInputStream s) throws IOException {
            int length = s.readInt();
            byte[] b = new byte[length];
            s.readFully(b);
            String str = new String(b);
            while (length++ % 4 != 0) {
                s.read();
            }
            return str;
        }

        protected float[] readFloatArray(LittleEndianDataInputStream s) throws IOException {
            int length = s.readInt() / 4;
            float[] fs = new float[length];
            for (int i = 0; i < length; ++i) {
                fs[i] = s.readFloat();
            }
            return fs;
        }

        protected double[] readDoubleArray(LittleEndianDataInputStream s) throws IOException {
            int length = s.readInt() / 8;
            double[] fs = new double[length];
            for (int i = 0; i < length; ++i) {
                fs[i] = s.readDouble();
            }
            return fs;
        }

        protected int[] readIntArray(LittleEndianDataInputStream s) throws IOException {
            int length = s.readInt() / 4;
            int[] is = new int[length];
            for (int i = 0; i < length; ++i) {
                is[i] = s.readInt();
            }
            return is;
        }

        protected void writeString(LittleEndianDataOutputStream s, String str) throws IOException {
            byte[] b = str.getBytes(Charsets.UTF_8);
            int length = b.length;
            s.writeInt(length);
            s.write(b);
            while (length++ % 4 != 0) {
                s.write(0);
            }
        }

        protected void writeStringBinary(LittleEndianDataOutputStream s, byte[] data) throws IOException {
            int length = data.length;
            s.writeInt(length);
            s.write(data);
            while (len++ % 4 != 0) {
                s.write(0);
            }
        }

        protected void writeStringBinary(LittleEndianDataOutputStream s, InputStream inputStream, int length) throws IOException {
            s.writeInt(length);
            IOUtils.copy(inputStream, s);
            while (length++ % 4 != 0) {
                s.write(0);
            }
        }
    }

    static class Hello extends Command {

        private String string;

        public String getString() {
            return string;
        }

        Hello() {
            super(HELLO);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            string = readString(s);
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
            throw new UnsupportedOperationException();
        }
    }

    static class More extends Command {

        private Boolean more;

        public Boolean hasMore() {
            return more;
        }

        More() {
            super(MORE);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            more = s.readInt() == 1;
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
            throw new UnsupportedOperationException();
        }
    }

    static class IfcModel extends Command {

        private final InputStream ifcInputStream;
        private long length = -1;

        IfcModel(InputStream ifcInputStream) {
            super(IFC_MODEL);
            this.ifcInputStream = ifcInputStream;
        }

        IfcModel(InputStream ifcInputStream, long length) {
            super(IFC_MODEL);
            this.ifcInputStream = ifcInputStream;
            this.length = length;
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) throws IOException {
            if (length == -1) {
                // This is now the point where memory problems will arise for large models
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(ifcInputStream, baos);
                writeStringBinary(s, baos.toByteArray());
            } else {
                writeStringBinary(s, ifcInputStream, (int) length);
            }
        }
    }

    static class Get extends Command {

        Get() {
            super(GET);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
        }
    }

    static class Next extends Command {

        Next() {
            super(NEXT);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
        }
    }

    static class Bye extends Command {

        Bye() {
            super(BYE);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
        }
    }

    static class Entity extends Command {

        private IfcGeomServerClientEntity entity;

        Entity() {
            super(ENTITY);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s0) throws IOException {
            byte[] message = new byte[len];
            s0.readFully(message, 0, len);
            ByteArrayInputStream bis = new ByteArrayInputStream(message);
            LittleEndianDataInputStream s = new LittleEndianDataInputStream(bis);
            entity = new IfcGeomServerClientEntity(
                    s.readInt(),
                    readString(s),
                    readString(s),
                    readString(s),
                    s.readInt(),
                    readDoubleArray(s),
                    s.readInt(),
                    readFloatArray(s),
                    readFloatArray(s),
                    readIntArray(s),
                    readFloatArray(s),
                    readIntArray(s),
                    readRemainder(bis)
            );
        }

        private String readRemainder(ByteArrayInputStream bis) {
            if (bis.available() == 0) {
                return null;
            }
            byte[] remainder = new byte[bis.available()];
            bis.read(remainder, 0, remainder.length);
            return new String(remainder);
        }

        public IfcGeomServerClientEntity getEntity() {
            return entity;
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
            throw new UnsupportedOperationException();
        }
    }

    static class GetLog extends Command {

        GetLog() {
            super(GET_LOG);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
        }
    }

    static class Log extends Command {

        private String string;

        Log() {
            super(LOG);
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            string = readString(s);
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) {
            throw new UnsupportedOperationException();
        }

        public String getString() {
            return string;
        }
    }

    static class Deflection extends Command {

        private final double deflection;

        Deflection(double deflection) {
            super(DEFLECTION);
            this.deflection = deflection;
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) throws IOException {
            s.writeDouble(deflection);
        }
    }

    static class Setting extends Command {

        private final int id;
        private final int value;

        public enum SettingId {
            APPLY_LAYERSETS(1 << 17);

            private final int id;

            SettingId(int id) {
                this.id = id;
            }

            private int getId() {
                return id;
            }
        }

        Setting(SettingId i, boolean b) {
            super(SETTING);
            this.id = i.getId();
            this.value = b ? 1 : 0;
        }

        @Override
        void read_contents(LittleEndianDataInputStream s) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        void write_contents(LittleEndianDataOutputStream s) throws IOException {
            s.writeInt(id);
            s.writeInt(value);
        }
    }

    private void terminate() throws RenderEngineException {
        hasMore = false;
        if (process == null) {
            return;
        }

        // Try and get the conversion log and say goodbye to the executable
        try {
            GetLog gl = new GetLog();
            gl.write(dos);

            if (dis.readInt() != LOG) {
                LOGGER.error("Invalid command sequence encountered");
                throw new IOException();
            }

            Log lg = new Log();
            lg.read(dis);

            final String log = lg.getString().trim();
            if (log.length() > 0) {
                LOGGER.info("\n" + log);
            }

            Bye b = new Bye();
            b.write(dos);

            if (dis.readInt() != BYE) {
                LOGGER.error("Invalid command sequence encountered");
                throw new IOException();
            }
            b.read(dis);
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("Exception during process termination", e);
        }

        try {
            // Give the executable some time to terminate by itself or kill
            // it after 2 seconds have passed
            for (int n = 0;;) {
                try {
                    if (process.exitValue() != 0) {
                        // LOGGER.error(String.format("Exited with non-zero exit code: %d", process.exitValue()));
                        throw new RenderEngineException(String.format("Exited with non-zero exit code: %d", process.exitValue()));
                    }
                    break;
                } catch (IllegalThreadStateException e) {
                    if (n++ == 20) {
                        process.destroy();
                        LOGGER.error("Forcefully terminated IfcOpenShell process");
                        break;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    LOGGER.trace("Exception during invoking Thread.sleep()", e);
                }
            }
        } finally {
            process.destroyForcibly();
        }

        dis = null;
        dos = null;
        process = null;
    }

    private void askForMore() throws IOException {
        hasMore = false;
        if (dis.readInt() != MORE) {
            LOGGER.error("Invalid command sequence encountered");
            throw new IOException();
        }

        More mr = new More();
        mr.read(dis);

        hasMore = mr.hasMore();
    }

    public IfcGeomServerClientEntity getNext() throws RenderEngineException {
        try {
            Get g = new Get();
            g.write(dos);

            if (dis.readInt() != ENTITY) {
                LOGGER.error("Invalid command sequence encountered");
                throw new IOException();
            }
            Entity e = new Entity();
            e.read(dis);

            Next n = new Next();
            n.write(dos);

            askForMore();

            return e.getEntity();
        } catch (IOException e) {
            terminate();
            return null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasNext() {
        return hasMore;
    }

    public String getVersion() {
        return VERSION;
    }
}
