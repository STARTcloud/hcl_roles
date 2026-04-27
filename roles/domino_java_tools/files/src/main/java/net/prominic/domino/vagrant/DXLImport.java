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
                importer.setReplaceDbProperties(true);  // allow replacing database properties
                importer.setReplicaRequiredForReplaceOrUpdate(false);  // don't require a matching replica ID in the DXL
                importer.setAclImportOption(aclImportOption);   // configurable; see --acl-import
                importer.setDesignImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);  // Create any missing design elements, overwrite existing design elements
                importer.setCompileLotusScript(true);  // Automatically compile any included LotusScript
                importer.setDocumentImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);   // allow importing documents.  Replace existing documents (replicaID and universal ID must match)
                importer.importDxl(stream, database);

                System.out.println("## Log:  " + importer.getLogComment());
                System.out.println(importer.getLog());
                System.out.println("## End Log");
                System.out.println("Imported " + importer.getImportedNoteCount() + " elements");
                // TODO: iterate over imported elements if log is insufficient
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
