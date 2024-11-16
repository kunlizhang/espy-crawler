package cis5550.kvs;

import cis5550.kvs.datastore.DatastoreContainer;
import cis5550.tools.Logger;
import cis5550.webserver.Request;
import cis5550.webserver.Route;

import java.io.*;
import java.util.SortedMap;
import java.util.stream.Stream;

import static cis5550.kvs.datamodels.IPPort.fromString;
import static cis5550.kvs.HTMLGenerator.generateTableEntries;
import static cis5550.kvs.HTMLGenerator.generateWorkerEntries;
import static cis5550.kvs.IDGenerator.generateLowerCaseID;
import static cis5550.utils.HTTPStatus.*;
import static cis5550.webserver.Server.*;

public class Worker extends cis5550.generic.Worker {
    public static final Logger LOGGER = Logger.getLogger(Worker.class);

    public static final String ID_FILE = "id";
    public static final int ID_LENGTH = 5;
    public static final int PAGE_SIZE = 10;

    private static DatastoreContainer theData;
    private static WorkerReplicationManager theReplicationManager;

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: Worker <port> <directory> <coordinator ip:port>");
            System.exit(1);
        }

        int myPort = Integer.valueOf(args[0]);
        String myDirectory = args[1];
        String myCoordinatorIPPort = args[2];

        if (!myCoordinatorIPPort.contains(":")) {
            System.err.println("Usage: Worker <port> <directory> <coordinator ip:port>");
            System.exit(1);
        }

        LOGGER.info("Starting KVS worker on port " + myPort + " with directory " + myDirectory + " and coordinator "
                + myCoordinatorIPPort);

        File myDir = new File(myDirectory);

        if (!myDir.exists() || !myDir.isDirectory()) {
            if (!myDir.mkdirs()) {
                LOGGER.error("Failed to create directory");
                System.exit(1);
            }
        }

        String myId = null;
        File myIdFile = new File(myDirectory + File.separator + ID_FILE);
        if (myIdFile.exists()) {
            try {
                myId = new BufferedReader(new FileReader(myIdFile)).readLine();
            } catch (IOException e) {
                LOGGER.error("Failed to read id file");
                System.exit(1);
                return;
            }
        }
        if (myId == null) {
            myId = generateLowerCaseID(ID_LENGTH);
            try {
                BufferedWriter myWriter = new BufferedWriter(new FileWriter(myIdFile));
                myWriter.write(myId);
                myWriter.flush();
                myWriter.close();
            } catch (IOException e) {
                LOGGER.error("Failed to write id file");
                System.exit(1);
                return;
            }
        }

        theData = new DatastoreContainer(myDirectory);
        theReplicationManager = new WorkerReplicationManager(myId, fromString(myCoordinatorIPPort));

        port(myPort);
        startPingThread(myId, myPort, myCoordinatorIPPort);

        get("/", getTables());
        before((req, res) -> {
            LOGGER.debug("Received request: " + req.requestMethod() + " " + req.url());
        });
        get("/view/:table", getTableRows());
        get("/data/:table/:row", getRow());
        get("/data/:table", streamRows());
        put("/data/:table/:row/:column", putCell());
        get("/data/:table/:row/:column", getCell());
        put("/delete/:table", deleteTable());
        put("/rename/:table", renameTable());
        get("/count/:table", rowCount());
        after((req, res) -> {
            LOGGER.debug("Completed request " + req.requestMethod() + " " + req.url());
        });
    }

    private static Route putCell() {
        return (req, res) -> {
            forwardPutRequest(req);
            String myTable = req.params("table");
            String myRow = req.params("row");
            String myColumn = req.params("column");
            byte[] myValue = req.bodyAsBytes();

            if (myTable == null || myRow == null || myColumn == null || myValue == null) {
                LOGGER.debug("Bad Request: " + myTable + " " + myRow + " " + myColumn + " " + myValue);
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            String myIfColumn = req.queryParams("ifcolumn");
            String myIfEquals = req.queryParams("equals");

            if (myIfColumn != null && myIfEquals != null) {
                Row myRowObject = theData.get(myTable, myRow);
                if (myRowObject == null
                        || myRowObject.get(myIfColumn) == null
                        || !myIfEquals.equals(myRowObject.get(myIfColumn))) {
                    setResponseStatus(res, PRECONDITION_FAILED);
                    return "FAIL";
                }
            }

            int myVersion = theData.put(myTable, myRow, myColumn, myValue);

            res.header("Version", String.valueOf(myVersion));
            setResponseStatus(res, OK);
            return "OK";
        };
    }

    private static Route getRow() {
        return (req, res) -> {
            String myTable = req.params("table");
            String myRow = req.params("row");

            if (myTable == null || myRow == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            Row myRowObject = theData.get(myTable, myRow);
            if (myRowObject == null) {
                setResponseStatus(res, NOT_FOUND);
                return "Not Found";
            }

            setResponseStatus(res, OK);
            res.type("application/octet-stream");
            res.bodyAsBytes(myRowObject.toByteArray());
            return null;
        };
    }

    private static Route getCell() {
        return (req, res) -> {
            String myTable = req.params("table");
            String myRow = req.params("row");
            String myColumn = req.params("column");

            if (myTable == null || myRow == null || myColumn == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            String myVersionString = req.queryParams("version");
            int myVersion;
            Row myRowObject;

            if (myVersionString == null) {
                myRowObject = theData.get(myTable, myRow);
                if (myRowObject == null) {
                    setResponseStatus(res, NOT_FOUND);
                    return "Not Found";
                }

                myVersion = theData.getVersion(myTable, myRow);
            } else {
                try {
                    myVersion = Integer.parseInt(myVersionString);
                } catch (NumberFormatException e) {
                    LOGGER.error("Failed to parse version", e);
                    setResponseStatus(res, BAD_REQUEST);
                    return "Bad Request";
                }

                myRowObject = theData.get(myTable, myRow, myVersion);
                if (myRowObject == null) {
                    setResponseStatus(res, NOT_FOUND);
                    return "Not Found";
                }
            }

            if (myRowObject.get(myColumn) == null) {
                setResponseStatus(res, NOT_FOUND);
                return "Not Found";
            }

            setResponseStatus(res, OK);
            res.bodyAsBytes(myRowObject.getBytes(myColumn));
            res.header("Version", String.valueOf(myVersion));
            return null;
        };
    }

    private static Route streamRows() {
        return (req, res) -> {
            String myTable = req.params("table");
            String myStartRow = req.queryParams("startRow");
            String myEndRowExclusive = req.queryParams("endRowExclusive");

            if (myTable == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }
            Stream<Row> myRowStream = theData.getRowDataStream(myTable, myStartRow, myEndRowExclusive);
            if (myRowStream == null) {
                setResponseStatus(res, NOT_FOUND);
                return "Not Found";
            }
            res.type("text/plain");
            myRowStream.forEach(myRow -> {
                try {
                    res.write(myRow.toByteArray());
                    res.write(new byte[] {'\n'});
                } catch (Exception e) {
                    LOGGER.error("Failed to write row to response", e);
                }
            });
            try {
                res.write(new byte[] {'\n'});
            } catch (Exception e) {
                LOGGER.error("Failed to write final LF", e);
            }
            return null;
        };
    }

    private static Route deleteTable() {
        return (req, res) -> {
            forwardPutRequest(req);
            String myTable = req.params("table");

            if (myTable == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            switch (theData.delete(myTable)) {
                case SUCCESS:
                    setResponseStatus(res, OK);
                    return "OK";
                case TABLE_NOT_FOUND:
                    setResponseStatus(res, NOT_FOUND);
                    return "Not found";
                default:
                    setResponseStatus(res, INTERNAL_SERVER_ERROR);
                    return "Internal Server Error";
            }
        };
    }

    private static Route renameTable() {
        return (req, res) -> {
            forwardPutRequest(req);
            String myTable = req.params("table");
            String myNewName = req.body();

            if (myTable == null || myNewName == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            switch (theData.rename(myTable, myNewName)) {
                case SUCCESS:
                    LOGGER.info("Renamed table " + myTable + " to " + myNewName);
                    setResponseStatus(res, OK);
                    return "OK";
                case TABLE_NOT_FOUND:
                    LOGGER.info("Table " + myTable + " not found");
                    setResponseStatus(res, NOT_FOUND);
                    return "Not found";
                case TABLE_ALREADY_EXISTS:
                    LOGGER.info("Table " + myNewName + " already exists");
                    setResponseStatus(res, CONFLICT);
                    return "Conflict";
                case WRONG_NAME_FORMAT:
                    LOGGER.info("Table name " + myNewName + " is invalid");
                    setResponseStatus(res, BAD_REQUEST);
                    return "Bad Request";
                default:
                    LOGGER.error("Failed to rename table");
                    setResponseStatus(res, INTERNAL_SERVER_ERROR);
                    return "Internal Server Error";
            }
        };
    }

    private static Route rowCount() {
        return (req, res) -> {
            String myTable = req.params("table");

            if (myTable == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }

            int myCount = theData.count(myTable);

            if (myCount < 0) {
                setResponseStatus(res, NOT_FOUND);
                return "Not Found";
            }

            setResponseStatus(res, OK);
            return myCount;
        };
    }

    private static Route getTables() {
        return (req, res) -> {
            setResponseStatus(res, OK);
            return generateWorkerEntries(theData.getTables());
        };
    }

    private static Route getTableRows() {
        return (req, res) -> {
            String myTable = req.params("table");
            String myFromRow = req.queryParams("fromRow");
            if (myTable == null) {
                setResponseStatus(res, BAD_REQUEST);
                return "Bad Request";
            }
            setResponseStatus(res, OK);
            SortedMap<String, Row> myRows = theData.getRows(myTable, myFromRow, PAGE_SIZE);

            return generateTableEntries(myTable, theData.getRows(myTable, myFromRow, PAGE_SIZE), PAGE_SIZE);
        };
    }

    private static void forwardPutRequest(Request aReq) {
        // turn off replication for now!
        return;
//        if (aReq.queryParams("replica") != null) {
//            return;
//        }
//        theReplicationManager.forwardPuts(aReq);
    }
}
