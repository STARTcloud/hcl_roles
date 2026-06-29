package net.prominic.domino.vagrant;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lotus.domino.*;

/**
 * Import a provided DXL file (or directory of DXL files) into the target database.
 * This will not do any validation of the DXL.  Importing to a production database is discouraged.
 *
 * <p>If the {@code <dxl-file>} argument is a directory, all {@code .dxl} files within
 * the directory and its subdirectories will be imported into the same database.  This
 * mode is intended for use with DominoBlueprint exports, which write each design element
 * to its own {@code .dxl} file.</p>
 *
 * <p>The ACL import behaviour is controlled by {@code --acl-import=<mode>}; see
 * {@link #parseAclImportMode(String)} for the mapping.  The default is
 * {@code update-else-create}, which preserves any target ACL entries not present in
 * the DXL while updating the ones that are and creating new ones for unmatched DXL
 * entries.  This default is set <b>explicitly</b> on every import so behaviour is
 * deterministic across Notes versions and call sites.</p>
 *
 * FUTURE TASK:  Validate as XML. Use the DXL schema if available
 */
public class DXLImport {

    private static final String APP_NAME = "DXLImport";
    private static final String USAGE =
        "java -jar DXLImport.jar [--acl-import=<mode>] <server> <database-name> <dxl-file-or-directory>";

    /**
     * Default ACL import behaviour applied when {@code --acl-import} is not given.
     * Matches the behaviour the tool has shipped with historically: existing ACL
     * entries (e.g. the stub {@code -Default-} entry on a freshly-created database)
     * are updated when the DXL contains a matching name, and entries that exist
     * only in the DXL are created.  Stub entries that the DXL does not mention
     * are <b>preserved</b> &mdash; if you want a clean clone of the source ACL,
     * pass {@code --acl-import=replace} instead.
     */
    public static final int DEFAULT_ACL_IMPORT_OPTION = DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE;

    public static void main(String[] args) {
        Session session = null;
        try {
            System.out.println("Application '" + APP_NAME + "' started.");

            // ----------------------------------------------------------------
            // Flag parsing pre-pass.  We strip recognised flags out of args[]
            // and then fall back to the original positional parsing below, so
            // existing callers that pass `<server> <database> <dxl>` still work.
            // ----------------------------------------------------------------
            int     aclImportOption = DEFAULT_ACL_IMPORT_OPTION;
            List<String> positional = new ArrayList<String>();

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("-h".equals(a) || "--help".equals(a)) {
                    printUsage();
                    return;
                }
                if (a.startsWith("--acl-import=")) {
                    aclImportOption = parseAclImportMode(a.substring("--acl-import=".length()));
                } else if ("--acl-import".equals(a)) {
                    if (i + 1 >= args.length) {
                        System.err.println("ERROR: --acl-import requires a value.");
                        System.err.println("USAGE:  " + USAGE);
                        System.exit(1);
                    }
                    aclImportOption = parseAclImportMode(args[++i]);
                } else if (a.startsWith("--")) {
                    System.err.println("ERROR: Unknown option '" + a + "'.");
                    System.err.println("USAGE:  " + USAGE);
                    System.exit(1);
                } else {
                    positional.add(a);
                }
            }

            if (positional.size() < 3) {
                System.err.println("ERROR: Not enough arguments.");
                System.err.println("USAGE:  " + USAGE);
                System.exit(1);
            }
            String server       = positional.get(0);
            String databaseName = positional.get(1);
            String dxlFileName  = positional.get(2);
            File dxlFile = new File(dxlFileName);
            if (!dxlFile.exists()) {
                System.err.println("DXL file not found at:  '" + dxlFile.getAbsolutePath() + ".");
                System.exit(1);
            }

            System.out.println("ACL import option: " + aclImportOptionName(aclImportOption));

            NotesThread.sinitThread();

            // If a password is available on the command line, use that when creating the session
            String password = System.getenv("PASSWORD");
            if (null == password || password.trim().isEmpty()) {
                System.out.println("No password found.");
                session = NotesFactory.createSession();
            }
            else {
                System.out.println("Password found.");
                session = NotesFactory.createSession((String)null, (String)null, password);
            }
            System.out.println("Running as user: '" + session.getUserName() + "'.");

            if (dxlFile.isDirectory()) {
                importDXLDirectory(session, server, databaseName, dxlFile, aclImportOption);
            }
            else {
                importDXL(session, server, databaseName, dxlFile, aclImportOption);
            }


            System.out.println("names.nsf was successfully created.");


        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            System.exit(1);  // trigger an error for scripting
        }
        finally {
            try {
                if (null != session) {
                    session.recycle();
                }
            }
            catch(NotesException ex) {
                ex.printStackTrace();
            }
            NotesThread.stermThread();
            System.out.println("Application '" + APP_NAME + "' completed.");
        }
    }

    /**
     * Print CLI usage / help text to stdout.
     */
    private static void printUsage() {
        System.out.println(USAGE);
        System.out.println();
        System.out.println("Imports a single DXL file, or every .dxl file under a directory");
        System.out.println("(recursively, alphabetical order), into the target database.");
        System.out.println();
        System.out.println("Positional arguments:");
        System.out.println("  server                    Domino server name; use \"\" for local");
        System.out.println("  database-name             Target database, e.g. apps/mydb.nsf");
        System.out.println("  dxl-file-or-directory     A .dxl file or a directory containing .dxl files");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --acl-import=<mode>       How the ACL in the DXL is applied to the target.");
        System.out.println("                            Default: update-else-create.");
        System.out.println("                            Modes (case-insensitive):");
        System.out.println("                              ignore               – Skip ACL in DXL.");
        System.out.println("                              create               – Set ACL only if target has none.");
        System.out.println("                              replace              – Replace target ACL with DXL ACL.");
        System.out.println("                                                     (alias of replace-else-ignore)");
        System.out.println("                              replace-else-ignore  – Same as 'replace'.");
        System.out.println("                              replace-else-create  – Replace if present, create if not.");
        System.out.println("                              update               – Update matched entries, create new ones.");
        System.out.println("                                                     (alias of update-else-create — default)");
        System.out.println("                              update-else-create   – Same as 'update'.");
        System.out.println("                              update-else-ignore   – Update matched entries, ignore new ones.");
        System.out.println("  -h, --help                Show this help and exit.");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  PASSWORD                  Notes ID password (optional)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Default: merge DXL ACL into target, preserving target-only entries");
        System.out.println("  java -jar DXLImport.jar \"\" apps/mydb.nsf ./export/acl/acl.dxl");
        System.out.println();
        System.out.println("  # Clean clone: target ACL is replaced exactly with the DXL ACL");
        System.out.println("  java -jar DXLImport.jar --acl-import=replace \"\" apps/mydb.nsf ./export/acl/acl.dxl");
        System.out.println();
        System.out.println("  # Keep target ACL untouched (e.g. when re-importing design only)");
        System.out.println("  java -jar DXLImport.jar --acl-import=ignore \"\" apps/mydb.nsf ./export/");
    }

    /**
     * Map a {@code --acl-import} CLI value to the corresponding
     * {@code DxlImporter.DXLIMPORTOPTION_*} constant.  Matching is case-insensitive
     * and accepts hyphenated, underscored, and shorthand forms.
     *
     * @throws IllegalArgumentException for any unrecognised value, with a message
     *         that lists the accepted modes.  The caller is responsible for
     *         translating this into a CLI error.
     */
    static int parseAclImportMode(String raw) {
        if (null == raw) {
            throw new IllegalArgumentException("--acl-import requires a value (use --help to see modes)");
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        if ("ignore".equals(key))                                            return DxlImporter.DXLIMPORTOPTION_IGNORE;
        if ("create".equals(key))                                            return DxlImporter.DXLIMPORTOPTION_CREATE;
        if ("replace".equals(key) || "replace-else-ignore".equals(key))      return DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE;
        if ("replace-else-create".equals(key))                               return DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE;
        if ("update".equals(key) || "update-else-create".equals(key))        return DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE;
        if ("update-else-ignore".equals(key))                                return DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_IGNORE;

        throw new IllegalArgumentException(
            "Unknown --acl-import mode: '" + raw + "'.  Accepted: ignore, create, " +
            "replace, replace-else-ignore, replace-else-create, " +
            "update, update-else-create, update-else-ignore");
    }

    /**
     * Return the canonical name (matching the {@code DXLIMPORTOPTION_*} constant)
     * for log/echo output, so the user can see exactly what behaviour is in effect.
     */
    static String aclImportOptionName(int option) {
        if (option == DxlImporter.DXLIMPORTOPTION_IGNORE)              return "IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_CREATE)              return "CREATE";
        if (option == DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE) return "REPLACE_ELSE_IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE) return "REPLACE_ELSE_CREATE";
        if (option == DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_IGNORE)  return "UPDATE_ELSE_IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE)  return "UPDATE_ELSE_CREATE";
        return "UNKNOWN(" + option + ")";
    }

    // -----------------------------------------------------------------------
    // importDXL — single file
    // -----------------------------------------------------------------------

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXL(Session session, String server, String databaseName, File dxlFile)
            throws NotesException, Exception {
        importDXL(session, server, databaseName, dxlFile, DEFAULT_ACL_IMPORT_OPTION);
    }

    public static void importDXL(Session session, String server, String databaseName, File dxlFile,
                                 int aclImportOption) throws NotesException, Exception {
        Database database = null;
        try {
            database = session.getDatabase(server, databaseName, false);
            if (null == database || !database.isOpen()) {
                throw new Exception("Could not open database '" + databaseName + "'.");
            }
            importDXL(session, database, dxlFile, aclImportOption);
        }
        finally {
            if (null != database) {
                database.recycle();
            }
        }
    }

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXL(Session session, Database database, File dxlFile)
            throws NotesException, Exception {
        importDXL(session, database, dxlFile, DEFAULT_ACL_IMPORT_OPTION);
    }

    /**
     * Import a single DXL file into the given (already-opened) database.
     * This overload is used when the database should remain open across multiple imports
     * (see {@link #importDXLDirectory}), so callers are responsible for opening and recycling
     * the database themselves.
     *
     * @param aclImportOption One of the {@code DxlImporter.DXLIMPORTOPTION_*} constants.
     *                        Always set explicitly so behaviour is deterministic.
     */
    public static void importDXL(Session session, Database database, File dxlFile,
                                 int aclImportOption) throws NotesException, Exception {
        Stream stream = null;
        DxlImporter importer = null;

        try {
            // https://help.hcl-software.com/dom_designer/14.0.0/basic/H_IMPORTDXL_METHOD_IMPORTER_JAVA.html
            // https://help.hcl-software.com/dom_designer/14.0.0/basic/H_EXAMPLES_NOTESDXLIMPORTER_CLASS_JAVA.html
            stream = session.createStream();
            if (stream.open(dxlFile.getAbsolutePath()) & (stream.getBytes() >0)) {
                // Import DXL from file to new database
                importer = session.createDxlImporter();
                // Database-level properties (e.g. <launchsettings>) live in the <database>
                // wrapper, not in a note. A DominoBlueprint export gives every design file a
                // bare <database> wrapper with no such properties, so enabling property
                // replacement for every file makes the alphabetically-last file win and
                // silently resets launch settings to default. Enable it only for files that
                // actually carry a database-property block (see fileDeclaresDbProperties).
                boolean replaceDbProperties = fileDeclaresDbProperties(dxlFile);
                importer.setReplaceDbProperties(replaceDbProperties);
                if (replaceDbProperties) {
                    System.out.println("  (database properties in this file will be applied to the target)");
                }
                importer.setReplicaRequiredForReplaceOrUpdate(false);  // don't require a matching replica ID in the DXL
                importer.setAclImportOption(aclImportOption);   // configurable; see --acl-import
                importer.setDesignImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);  // Create any missing design elements, overwrite existing design elements
                importer.setCompileLotusScript(true);  // Automatically compile any included LotusScript
                importer.setDocumentImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);   // allow importing documents.  Replace existing documents (replicaID and universal ID must match)
                importer.importDxl(stream, database);

                // Capture the log once so we can both print it and scan it for compile failures.
                String importLog = importer.getLog();
                System.out.println("## Log:  " + importer.getLogComment());
                System.out.println(importLog);
                System.out.println("## End Log");
                System.out.println("Imported " + importer.getImportedNoteCount() + " elements");
                // TODO: iterate over imported elements if log is insufficient

                // A code-compile failure is reported by DxlImporter as a *log warning*
                // (HCL log id 7005), not as a thrown NotesException: the design note is still
                // created and counted as imported, so it would otherwise pass silently.
                warnIfCompileErrors(dxlFile, importLog);
            }
        }
        finally {
            if (null != stream) {
                stream.recycle();
            }
            if (null != importer) {
                importer.recycle();
            }
        }
    }

    // -----------------------------------------------------------------------
    // importDXLDirectory — walk a directory of .dxl files
    // -----------------------------------------------------------------------

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir)
            throws NotesException, Exception {
        importDXLDirectory(session, server, databaseName, dxlDir, DEFAULT_ACL_IMPORT_OPTION);
    }

    /**
     * Import every {@code .dxl} file under the given directory (recursively) into the target
     * database.  The database is opened once and reused across all files.
     *
     * <p>Files are imported in alphabetical order of their absolute path to keep runs
     * reproducible.  If a single file fails to import, the error is logged and the remaining
     * files are still attempted; at the end, this method throws if any file failed, so the
     * process exits with a non-zero status.</p>
     *
     * @param aclImportOption Applied to every file in the directory.  This is intentional:
     *                        {@code acl.dxl} (in {@code acl/}) is the only file in a
     *                        DominoBlueprint export that contains an {@code <acl>} element,
     *                        so the option is a no-op for the other files.
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir,
                                          int aclImportOption) throws NotesException, Exception {
        if (null == dxlDir || !dxlDir.isDirectory()) {
            throw new Exception("DXL directory not found or not a directory: '" + (null == dxlDir ? "null" : dxlDir.getAbsolutePath()) + "'.");
        }

        List<File> dxlFiles = new ArrayList<File>();
        collectDXLFiles(dxlDir, dxlFiles);
        // sort by absolute path for reproducible import order
        Collections.sort(dxlFiles);

        System.out.println("Found " + dxlFiles.size() + " DXL file(s) under '" + dxlDir.getAbsolutePath() + "'.");
        if (dxlFiles.isEmpty()) {
            return;
        }

        Database database = null;
        int successCount = 0;
        List<String> failures = new ArrayList<String>();
        try {
            database = session.getDatabase(server, databaseName, false);
            if (null == database || !database.isOpen()) {
                throw new Exception("Could not open database '" + databaseName + "'.");
            }

            for (File file : dxlFiles) {
                System.out.println("--- Importing '" + file.getAbsolutePath() + "' ---");
                try {
                    importDXL(session, database, file, aclImportOption);
                    successCount++;
                }
                catch (Exception ex) {
                    // Continue past a single bad file so one failure doesn't abort the entire import.
                    System.err.println("Failed to import '" + file.getAbsolutePath() + "': " + ex.getMessage());
                    ex.printStackTrace();
                    failures.add(file.getAbsolutePath());
                }
            }
        }
        finally {
            if (null != database) {
                database.recycle();
            }
        }

        System.out.println("Directory import complete.  Imported: " + successCount + ", Failed: " + failures.size() + ".");
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(failures.size()).append(" DXL file(s) failed to import:");
            for (String path : failures) {
                sb.append("\n  - ").append(path);
            }
            throw new Exception(sb.toString());
        }
    }

    /**
     * Detect a code-compile failure in a DXL import log and print a short, actionable
     * warning when one is found.
     *
     * <p>When DxlImporter imports a Java agent, Java script library, or LotusScript element it
     * compiles the code at import time.  A compile failure is reported as a <em>log warning</em>
     * (HCL log id {@code 7005}, e.g. {@code "Java compile errors: ..."}), <strong>not</strong> as
     * a thrown {@link NotesException}.  The design note is still created and is included in
     * {@link DxlImporter#getImportedNoteCount()}, so without this check a broken element would
     * import "successfully" and only fail later at runtime.</p>
     *
     * <p>This is deliberately a warning rather than a hard failure.  The usual cause is
     * dependency <em>ordering</em>, not bad DXL: each element is compiled against whatever already
     * exists in the target database, so a referenced Java script library
     * ({@code <sharedlibraryref>}), a LotusScript library referenced via {@code Use}, or a
     * {@code .jar} file resource imported later is simply not on the build path yet.  Re-running
     * the import once every element and resource is present (or recompiling in Domino Designer)
     * typically clears it.  Dependency-aware import ordering is tracked as a separate enhancement
     * for the DominoBlueprint importer.</p>
     *
     * @return {@code true} if a compile error was detected and a warning was printed.
     */
    static boolean warnIfCompileErrors(File dxlFile, String importLog) {
        if (null == importLog || importLog.isEmpty()) {
            return false;
        }
        // Match the numeric HCL log id and the human-readable phrasing, which covers both
        // "Java compile errors" and "LotusScript ... compile error" wording across versions.
        boolean hasCompileError =
               importLog.contains("id='7005'")
            || importLog.contains("id=\"7005\"")
            || importLog.toLowerCase().contains("compile error");
        if (!hasCompileError) {
            return false;
        }
        String name = (null == dxlFile) ? "(unknown)" : dxlFile.getName();
        System.out.println("  [WARNING] '" + name + "' was imported as a design note, but its code");
        System.out.println("            did NOT compile (see the 'compile errors' in the log above).");
        System.out.println("            The note exists in the target database but will not run until it");
        System.out.println("            compiles cleanly.  This is normally a dependency-ordering issue:");
        System.out.println("            code is compiled at import time against whatever is already in the");
        System.out.println("            database, so a referenced script library or .jar file resource that");
        System.out.println("            is imported later is not yet on the build path.  Re-run the import");
        System.out.println("            once all elements/resources are present, or recompile in Designer.");
        return true;
    }

    /**
     * Return {@code true} if the DXL file carries a database-level property block in its
     * {@code <database>} wrapper &mdash; currently {@code <launchsettings>} (web/Notes launch
     * options) or {@code <databaseinfo>}, plus {@code <acl>} so ACL-bearing files keep their
     * historical {@code setReplaceDbProperties(true)} behaviour for attributes such as
     * {@code maxinternetaccess}.
     *
     * <p>Bare design-element files (a single {@code <form>}/{@code <view>}/... inside an
     * otherwise-empty {@code <database>} wrapper) return {@code false}, so importing them
     * with {@code setReplaceDbProperties(false)} cannot overwrite database properties that
     * an earlier file (e.g. {@code other/LaunchSettings.dxl}) just applied.</p>
     *
     * <p>The relevant block sits at the very top of the file, immediately after the
     * {@code <database>} open tag, so a short prefix is enough to detect it without reading
     * large base64 file-resource payloads.</p>
     */
    static boolean fileDeclaresDbProperties(File dxlFile) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(dxlFile);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            String head = new String(buf, 0, n, "UTF-8");
            return head.contains("<launchsettings")
                || head.contains("<databaseinfo")
                || head.contains("<acl");
        }
        catch (Exception e) {
            // On any read error, fall back to the safe default: do not replace properties.
            return false;
        }
        finally {
            if (null != in) {
                try { in.close(); } catch (Exception ignore) { /* best effort */ }
            }
        }
    }

    /**
     * Recursively collect every regular file with a {@code .dxl} extension (case-insensitive)
     * under {@code dir} into {@code out}.
     */
    private static void collectDXLFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (null == children) {
            return;  // unreadable or not a directory
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDXLFiles(child, out);
            }
            else if (child.isFile() && child.getName().toLowerCase().endsWith(".dxl")) {
                out.add(child);
            }
        }
    }
}
