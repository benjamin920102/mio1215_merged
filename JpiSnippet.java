import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/**
 * Decode Mio super(...) strings by invoking the REAL (int,int)->String methods
 * in the running Minecraft JVM. No jar rewriting or offline decryption.
 *
 * Requires ASM 9 in the snippet compiler, as before.
 * Execute with JpiSnippet.execute(System.out) after Minecraft has initialized.
 */
public class JpiSnippet {
    static final String DEFAULT_JAR =
        "C:\\Users\\Library\\Documents\\1215mine_mio\\.minecraft\\mods\\Mio-Compact-Fixed.jar";

    // You can explicitly provide the active KnotClassLoader if necessary.
    public static volatile ClassLoader TARGET_LOADER;

    static final class Hit {
        String clazz, parent, constructor, decoderClass, decoderMethod;
        int a, b;
        String value, error, usedLoader;

        String key() {
            return clazz + '|' + constructor + '|' + decoderClass + '|' +
                decoderMethod + '|' + a + '|' + b;
        }
    }

    static final class Candidate {
        final String name;
        final ClassLoader loader;
        Candidate(String name, ClassLoader loader) {
            this.name = name;
            this.loader = loader;
        }
    }

    static final class Decoder {
        final MethodHandle handle;
        final String loader;
        Decoder(MethodHandle handle, ClassLoader loader) {
            this.handle = handle;
            this.loader = loaderName(loader);
        }
    }

    public static void execute(PrintStream out) {
        LinkedHashMap<String, Hit> hits = new LinkedHashMap<>();
        try {
            Path jarPath = Paths.get(System.getProperty("super.jar", DEFAULT_JAR));
            if (!Files.isRegularFile(jarPath)) {
                out.println("JAR not found: " + jarPath);
                return;
            }
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        scan(in.readAllBytes(), hits);
                    } catch (Throwable t) {
                        out.println("Scan error " + entry.getName() + ": " + errorText(t));
                    }
                }
            }
            out.println("super(...) decoder calls found: " + hits.size());

            List<Candidate> loaders = discoverLoaders(out);
            out.println("ClassLoader candidates: " + loaders.size());
            if (!hits.isEmpty()) {
                String probe = hits.values().iterator().next().decoderClass.replace('/', '.');
                boolean canLoad = false;
                for (Candidate candidate : loaders) {
                    try {
                        Class<?> c = Class.forName(probe, false, candidate.loader);
                        out.println("  [OK] " + candidate.name + " => " + loaderName(c.getClassLoader()));
                        canLoad = true;
                    } catch (Throwable t) {
                        out.println("  [NO] " + candidate.name + " => " + errorText(t));
                    }
                }
                if (!canLoad) {
                    out.println("WARNING: no ClassLoader could locate Mio classes.");
                }
            }

            // Critical fix: getDeclaredMethod() resolves signatures of unrelated
            // methods and may fail if DiscordIPC / Baritone is absent.  findStatic()
            // resolves only the requested primitive-argument decoder method.
            Map<String, Decoder> cache = new HashMap<>();
            int decoded = 0, failed = 0;
            for (Hit hit : hits.values()) {
                try {
                    String key = hit.decoderClass + '#' + hit.decoderMethod;
                    Decoder decoder = cache.get(key);
                    if (decoder == null) {
                        Class<?> cls = findClass(hit.decoderClass.replace('/', '.'), loaders);
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                            cls, MethodHandles.lookup());
                        MethodHandle handle = lookup.findStatic(cls, hit.decoderMethod,
                            MethodType.methodType(String.class, int.class, int.class));
                        decoder = new Decoder(handle, cls.getClassLoader());
                        cache.put(key, decoder);
                    }
                    hit.usedLoader = decoder.loader;
                    hit.value = (String) decoder.handle.invokeExact(hit.a, hit.b);
                    decoded++;
                } catch (Throwable ex) {
                    hit.error = errorText(ex);
                    failed++;
                }
            }

            Path desktop = Paths.get(System.getProperty("user.home"), "Desktop");
            Files.createDirectories(desktop);
            Path output = desktop.resolve("super_mapping.json");
            Files.writeString(output, toJson(jarPath, hits.values(), decoded, failed),
                StandardCharsets.UTF_8);

            out.println("Decoded: " + decoded + ", failed: " + failed);
            out.println("Saved: " + output.toAbsolutePath());
            if (failed > 0) {
                Map<String, Integer> errors = new LinkedHashMap<>();
                for (Hit h : hits.values()) {
                    if (h.error == null) continue;
                    String kind = h.error.split(":", 2)[0];
                    errors.merge(kind, 1, Integer::sum);
                }
                out.println("Failure reasons: " + errors);
            }
        } catch (Throwable t) {
            t.printStackTrace(out);
        }
    }

    static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" :
            loader.getClass().getName() + "@" +
            Integer.toHexString(System.identityHashCode(loader));
    }

    static String errorText(Throwable t) {
        if (t == null) return "unknown";
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null ? "" : ": " + msg);
    }

    static void add(List<Candidate> list, String name, ClassLoader loader) {
        if (loader == null) return;
        for (Candidate existing : list) if (existing.loader == loader) return;
        list.add(new Candidate(name, loader));
    }

    static void addWithParents(List<Candidate> list, String name, ClassLoader loader) {
        for (int i = 0; loader != null && i < 12; i++) {
            add(list, i == 0 ? name : name + ".parent" + i, loader);
            try { loader = loader.getParent(); }
            catch (Throwable ignored) { break; }
        }
    }

    static List<Candidate> discoverLoaders(PrintStream out) {
        List<Candidate> found = new ArrayList<>();
        addWithParents(found, "TARGET_LOADER", TARGET_LOADER);
        addWithParents(found, "thread.context", Thread.currentThread().getContextClassLoader());
        addWithParents(found, "snippet.loader", JpiSnippet.class.getClassLoader());
        addWithParents(found, "system.loader", ClassLoader.getSystemClassLoader());

        try {
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> {
                    frames.limit(100).forEach(frame -> {
                        Class<?> c = frame.getDeclaringClass();
                        addWithParents(found, "stack:" + c.getName(), c.getClassLoader());
                    });
                    return null;
                });
        } catch (Throwable t) {
            out.println("Stack ClassLoader discovery: " + errorText(t));
        }

        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                addWithParents(found, "thread:" + thread.getName(), thread.getContextClassLoader());
            }
        } catch (Throwable t) {
            out.println("Thread ClassLoader discovery: " + errorText(t));
        }

        List<Candidate> fabricLoaders = new ArrayList<>();
        for (Candidate candidate : new ArrayList<>(found)) {
            try {
                Class<?> base = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncherBase", false, candidate.loader);
                Object launcher = base.getMethod("getLauncher").invoke(null);
                if (launcher == null) continue;
                Method getter = launcher.getClass().getMethod("getTargetClassLoader");
                ClassLoader target = (ClassLoader) getter.invoke(launcher);
                addWithParents(fabricLoaders, "FabricLauncher.target", target);
            } catch (Throwable ignored) {
                // Not every candidate loader exposes Fabric internals.
            }
        }

        List<Candidate> prioritized = new ArrayList<>();
        addWithParents(prioritized, "TARGET_LOADER", TARGET_LOADER);
        for (Candidate candidate : fabricLoaders)
            add(prioritized, candidate.name, candidate.loader);
        for (Candidate candidate : found)
            add(prioritized, candidate.name, candidate.loader);
        return prioritized;
    }

    static Class<?> findClass(String name, List<Candidate> candidates)
            throws ClassNotFoundException {
        Throwable last = null;
        for (Candidate candidate : candidates) {
            try {
                return Class.forName(name, false, candidate.loader);
            } catch (ClassNotFoundException | LinkageError ex) {
                last = ex;
            }
        }
        throw new ClassNotFoundException(name + " (last error: " + errorText(last) + ")", last);
    }

    // Instruction scan identifies integer pairs used in super(...) only.
    // It does not implement the decoder, and never changes the input JAR.
    static void scan(byte[] data, Map<String, Hit> hits) {
        new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9) {
            String owner, parent;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                parent = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc,
                                             String signature, String[] exceptions) {
                if (!"<init>".equals(method) || parent == null) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    final ArrayDeque<Integer> ints = new ArrayDeque<>(2);
                    final List<Hit> pending = new ArrayList<>();
                    boolean finished;

                    void reset() { ints.clear(); }
                    void integer(int n) {
                        if (ints.size() == 2) ints.removeFirst();
                        ints.addLast(n);
                    }

                    @Override public void visitInsn(int op) {
                        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
                            integer(op - Opcodes.ICONST_0);
                        else reset();
                    }
                    @Override public void visitIntInsn(int op, int operand) {
                        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) integer(operand);
                        else reset();
                    }
                    @Override public void visitLdcInsn(Object cst) {
                        if (cst instanceof Integer n) integer(n);
                        else reset();
                    }
                    @Override public void visitMethodInsn(int op, String callOwner,
                            String callName, String callDesc, boolean isInterface) {
                        if (!finished && op == Opcodes.INVOKESTATIC &&
                            "(II)Ljava/lang/String;".equals(callDesc) && ints.size() == 2) {
                            Hit h = new Hit();
                            h.clazz = owner;
                            h.parent = parent;
                            h.constructor = method + desc;
                            h.decoderClass = callOwner;
                            h.decoderMethod = callName;
                            Iterator<Integer> it = ints.iterator();
                            h.a = it.next();
                            h.b = it.next();
                            pending.add(h);
                        }
                        if (!finished && op == Opcodes.INVOKESPECIAL &&
                            "<init>".equals(callName)) {
                            if (callOwner.equals(parent)) {
                                for (Hit h : pending) hits.putIfAbsent(h.key(), h);
                                finished = true;
                            } else if (callOwner.equals(owner)) {
                                finished = true;
                            }
                        }
                        reset();
                    }
                    @Override public void visitVarInsn(int op, int var) { reset(); }
                    @Override public void visitTypeInsn(int op, String type) { reset(); }
                    @Override public void visitFieldInsn(int op, String a, String b, String c) { reset(); }
                    @Override public void visitJumpInsn(int op, Label label) { reset(); }
                    @Override public void visitIincInsn(int var, int increment) { reset(); }
                    @Override public void visitInvokeDynamicInsn(String name, String desc,
                            Handle bootstrap, Object... args) { reset(); }
                    @Override public void visitTableSwitchInsn(int min, int max,
                            Label dflt, Label... labels) { reset(); }
                    @Override public void visitLookupSwitchInsn(Label dflt,
                            int[] keys, Label[] labels) { reset(); }
                    @Override public void visitMultiANewArrayInsn(String desc, int dims) { reset(); }
                    @Override public void visitLabel(Label label) { reset(); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    static String toJson(Path jar, Collection<Hit> hits, int decoded, int failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"jar\": "); quote(sb, jar.toString());
        sb.append(",\n  \"decoded\": ").append(decoded);
        sb.append(",\n  \"failed\": ").append(failed);
        sb.append(",\n  \"mappings\": [\n");
        int index = 0;
        for (Hit h : hits) {
            if (index++ > 0) sb.append(",\n");
            sb.append("    {\n      \"class\": "); quote(sb, h.clazz.replace('/', '.'));
            sb.append(",\n      \"superClass\": "); quote(sb, h.parent.replace('/', '.'));
            sb.append(",\n      \"constructor\": "); quote(sb, h.constructor);
            sb.append(",\n      \"decoder\": ");
            quote(sb, h.decoderClass.replace('/', '.') + "." + h.decoderMethod + "(int,int)");
            sb.append(",\n      \"expression\": ");
            quote(sb, h.decoderMethod + "(" + h.a + ", " + h.b + ")");
            sb.append(",\n      \"value\": ");
            if (h.value == null) sb.append("null"); else quote(sb, h.value);
            sb.append(",\n      \"classLoader\": ");
            if (h.usedLoader == null) sb.append("null"); else quote(sb, h.usedLoader);
            sb.append(",\n      \"error\": ");
            if (h.error == null) sb.append("null"); else quote(sb, h.error);
            sb.append("\n    }");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int j = hex.length(); j < 4; j++) sb.append('0');
                        sb.append(hex);
                    } else sb.append(c);
            }
        }
        sb.append('"');
    }
}
