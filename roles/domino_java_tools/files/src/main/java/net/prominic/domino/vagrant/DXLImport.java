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
 * FUTURE TASK:  Validate as XML. Use the DXL schema if available
 */
public class DXLImport {

    private static final String APP_NAME = "DXLImport";
    private static final String USAGE = "java -jar DXLImport.jar <server> <database-name> <dxl-file-or-directory>";

    public static void main(String[] args) {
        Session session = null;
        try {
            System.out.println("Application '" + APP_NAME + "' started.");

			if (args.length < 3) {
				System.err.println("ERROR: Not enough arguments.");
				System.err.println("USAGE:  " + USAGE);
				System.exit(1);
			}
			String server = args[0];
			String databaseName = args[1];
			String dxlFileName = args[2];
			File dxlFile = new File(dxlFileName);
			if (!dxlFile.exists()) {
				System.err.println("DXL file not found at:  '" + dxlFile.getAbsolutePath() + ".");
				System.exit(1);
			}


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
                importDXLDirectory(session, server, databaseName, dxlFile);
            }
            else {
                importDXL(session, server, databaseName, dxlFile);
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


    public static void importDXL(Session session, String server, String databaseName, File dxlFile) throws NotesException, Exception {
        Database database = null;
        try {
            database = session.getDatabase(server, databaseName, false);
            if (null == database || !database.isOpen()) {
                throw new Exception("Could not open database '" + databaseName + "'.");
            }
            importDXL(session, database, dxlFile);
        }
        finally {
            if (null != database) {
                database.recycle();
            }
        }
    }

    /**
     * Import a single DXL file into the given (already-opened) database.
     * This overload is used when the database should remain open across multiple imports
     * (see {@link #importDXLDirectory}), so callers are responsible for opening and recycling
     * the database themselves.
     */
    public static void importDXL(Session session, Database database, File dxlFile) throws NotesException, Exception {
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
                importer.setAclImportOption(DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE);   // Create any missing ACL entries, overwrite existing entries
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

    /**
     * Import every {@code .dxl} file under the given directory (recursively) into the target
     * database.  The database is opened once and reused across all files.
     *
     * <p>Files are imported in alphabetical order of their absolute path to keep runs
     * reproducible.  If a single file fails to import, the error is logged and the remaining
     * files are still attempted; at the end, this method throws if any file failed, so the
     * process exits with a non-zero status.</p>
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir) throws NotesException, Exception {
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
                    importDXL(session, database, file);
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