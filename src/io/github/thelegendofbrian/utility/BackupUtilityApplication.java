package io.github.thelegendofbrian.utility;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.zeroturnaround.zip.ZipException;
import org.zeroturnaround.zip.ZipUtil;
import io.github.talkarcabbage.logger.LoggerManager;

public class BackupUtilityApplication {
	
	protected File configFile;
	protected Properties properties;
	
	protected String pathToBackups;
	protected File serversDirectory;
	protected File backupsDirectory;
	
	protected File[] serverList;
	protected File[] backupList;
	
	protected Date lastModified;
	
	protected HashMap<File, Date> serverMap = new HashMap<>();
	protected HashMap<File, Date> backupMap = new HashMap<>();
	protected ArrayList<File> serversToBackup = new ArrayList<>();
	
	protected static long mostRecentTime;
	
	// Defined as non-static to promote thread safety
	protected final SimpleDateFormat sdfPretty = new SimpleDateFormat("MMM dd yyyy - hh:mm:ss z");
	
	// Define config property literals
	protected static final String SERVERS_DIRECTORY = "serversDirectory";
	protected static final String BACKUPS_DIRECTORY = "backupsDirectory";
	protected static final String LOG_LEVEL = "logLevel";
	protected static final String ENABLE_PRUNING = "enablePruning";
	protected static final String PRUNING_THRESHOLD = "pruningThreshold";
	
	// Define other literals
	protected static final String CONFIG_NAME = "config.ini";
	
	protected static final Logger logger = LoggerManager.getInstance().getLogger("main");
	
	public BackupUtilityApplication() {
		sdfPretty.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	public static void main(String[] args) {
		LoggerManager.getInstance().getFormatter().setLoggerNameLevel(Level.FINE);
		
		BackupUtilityApplication instance = new BackupUtilityApplication();
		instance.runBackupUtility();
	}
	
	public void runBackupUtility() {
		configSetup();
		storeServersDirectories();
		checkDirectories();
		createBackupDirectories();
		storeEachServerLastModified();
		storeBackupsDirectories();
		determineServersToBackup();
		backupServers();
		logger.info("Backup process complete.");
	}
	
	/**
	 * Sets default config values and loads the config file.
	 */
	public void configSetup() {
		logger.fine("Reading configuration file.");
		
		Properties defaultProps = new Properties();
		defaultProps.setProperty(LOG_LEVEL, "CONFIG");
		defaultProps.setProperty(ENABLE_PRUNING, "false");
		defaultProps.setProperty(PRUNING_THRESHOLD, "60");
		
		properties = new Properties(defaultProps);
		properties.setProperty(SERVERS_DIRECTORY, "");
		properties.setProperty(BACKUPS_DIRECTORY, "");
		
		configFile = new File(CONFIG_NAME);
		
		// Load the config file into properties
		loadConfig();
		
		// Verify validity of config values
		if ("".equals(properties.getProperty(SERVERS_DIRECTORY)) || "".equals(properties.getProperty(BACKUPS_DIRECTORY))) {
			// TODO: Add GUI and explanation of how to set up for no GUI
			logger.severe("Invalid directory configuration set.");
			logger.severe( () -> "Edit " + CONFIG_NAME + " and specify the " + SERVERS_DIRECTORY + " and " + BACKUPS_DIRECTORY + ".");
			crashProgram();
		}
		
		// Apply the settings from config
		LoggerManager.getInstance().setGlobalLoggingLevel(Level.parse(properties.getProperty(LOG_LEVEL)));
		logger.fine("Logging level found in config: " + properties.getProperty(LOG_LEVEL));
		
		String pathToServers;
		pathToServers = properties.getProperty(SERVERS_DIRECTORY);
		serversDirectory = new File(pathToServers);
		logger.fine("Servers directory found in config: " + serversDirectory.getAbsolutePath());
		
		pathToBackups = properties.getProperty(BACKUPS_DIRECTORY);
		backupsDirectory = new File(pathToBackups);
		logger.fine("Backups directory found in config: " + backupsDirectory.getAbsolutePath());
	}
	
	/**
	 * Attempts to load the config file into {@link #properties}. If the config file doesn't exist, it will be created.
	 */
	public void loadConfig() {
		// Try to create a config file if one doesn't exist
		try {
			if (configFile.createNewFile()) {
				logger.info("Configuration file \"" + configFile.getName() + "\" created successfully.");
				saveConfig();
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Unable to create configuration file: ", e);
			crashProgram();
		}
		
		try (InputStream in = new FileInputStream(configFile)) {
			properties.load(in);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Unable to load configuration file: ", e);
			crashProgram();
		}
	}
	
	/**
	 * Attempts to save the properties into the config file
	 */
	public void saveConfig() {
		try (OutputStream out = new FileOutputStream(configFile)) {
			properties.store(out, null);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Unable to save to configuration file: ", e);
			crashProgram();
		}
	}
	
	/**
	 * Stores the list of directories in the servers directory into {@link #serversList}.
	 */
	protected void storeServersDirectories() {
		serverList = serversDirectory.listFiles(File::isDirectory);
	}
	
	/**
	 * Stores the list of directories in the backups directory into {@link #backupsList}.
	 */
	protected void storeBackupsDirectories() {
		backupList = backupsDirectory.listFiles(File::isDirectory);
	}
	
	/**
	 * Verifies that {@link #serverDirectory} points to an existing directory and sets up and verifies the usability of the directory pointed to by {@link #backupsDirectory}.
	 */
	protected void checkDirectories() {
		// Check if servers directory exists
		logger.fine("Checking for valid server file structure.");
		if (!serversDirectory.exists()) {
			logger.severe("The specified servers directory " + serversDirectory.getAbsolutePath() + " does not exist.");
			crashProgram();
		}
		
		// Make backups directory if it doesn't exist
		logger.fine("Checking for backups directory.");
		
		if (backupsDirectory.mkdir()) {
			logger.info("Backups folder \"" + backupsDirectory.getName() + "\" created successfully.");
		}
		
		if (!backupsDirectory.canWrite()) {
			logger.severe("The specified backups directory cannot be written to.");
			crashProgram();
		}
		
		if (serverList.length == 0) {
			logger.severe("The servers directory does not contain any server folders.");
			crashProgram();
		}
	}
	
	/**
	 * Creates the backup folder directory structure based on the servers in the servers directory.
	 */
	protected void createBackupDirectories() {
		File serverBackupFolder;
		for (File server : serverList) {
			serverBackupFolder = new File(backupsDirectory.getAbsolutePath() + File.separator + server.getName());
			if (serverBackupFolder.mkdir()) {
				logger.info("Backup directory did not exist for \"" + server.getName() + "\". Creating directory.");
			}
		}
	}
	
	/**
	 * Finds the most recently changed file in each server directory and stores when it was last modified.
	 */
	protected void storeEachServerLastModified() {
		logger.fine("Checking when each server was last modified.");
		
		for (File serverDir : serverList) {
			lastModified = roundDateToSeconds(lastModifiedInFolder(serverDir));
			logger.fine(() -> "Found server named: \"" + serverDir.getName() + "\" last modified: " + sdfPretty.format(lastModified));
			serverMap.put(serverDir, lastModified);
		}
	}
	
	/**
	 * Determines which servers need to be backed up. Gets the most recent time stamp in each backup directory. If {@link #backupList()} is empty, all servers will be marked to back up.
	 */
	public void determineServersToBackup() {
		if (backupList.length != 0) {
			// Figure out the time stamps for the most recent backup for each server
			parseBackupTimeStamps();
			
			// Check which servers have been modified since the last backup
			compareServerAndBackupTimestamps();
		} else {
			logger.fine("No backups were found. All servers will be backed up.");
			for (File server : serverList) {
				serversToBackup.add(server);
			}
		}
	}

	/**
	 * Stores the time stamps for the most recent backup for each server into {@link #backupMap}.
	 * If the backup folder for a server is empty, the time stamp for the most recent backup is set to epoch time.
	 */
	protected void parseBackupTimeStamps() {
		for (File backupDir : backupList) {
			// If the backup directory for a server is empty, make a backup for that server
			if (backupDir.list().length == 0) {
				logger.fine("Backup directory for server \"" + backupDir.getName() + "\" is empty. A backup will be made.");
				backupMap.put(backupDir, new Date(0L));
			} else {
				lastModified = roundDateToSeconds(getBackupTimeStamp(getLatestBackup(backupDir)));
				logger.fine(() -> "Found most recent backup for server: \"" + backupDir.getName() + "\" last modified: " + sdfPretty.format(lastModified));
				backupMap.put(backupDir, lastModified);
			}
		}
	}
	
	protected void compareServerAndBackupTimestamps() {
		logger.fine("Checking which servers need to be backed up.");
		Date backupLastModified;
		for (Map.Entry<File, Date> entry : serverMap.entrySet()) {
			File serverFile = entry.getKey();
			Date serverLastModified = entry.getValue();
			
			backupLastModified = backupMap.get(generateBackupFileFromString(serverFile.getName(), pathToBackups));
			if (serverLastModified.compareTo(backupLastModified) > 0) {
				logger.fine("Server \"" + serverFile.getName() + "\" needs to be backed up.");
				serversToBackup.add(serverFile);
			}
		}
	}

	/**
	 * Iterates through the servers that need to be backed up.
	 */
	protected void backupServers() {
		if (serversToBackup.isEmpty()) {
			logger.info("All backups were already up-to-date.");
		} else {
			for (File serverFolder : serversToBackup) {
				logger.info("Backing up server: " + serverFolder.getName());
				
				File backupFolder;
				backupFolder = generateBackupFileFromString(serverFolder.getName(), pathToBackups);
				try {
					backupSpecificServer(serverFolder, backupFolder);
				} catch (ZipException e) {
					logger.log(Level.WARNING, "Server folder \"" + serverFolder.getName() + "\" contains no files: ", e);
				}
			}
		}
	}
	
	/**
	 * Rounds the Date object to the nearest second.
	 * 
	 * @param date
	 * @return
	 */
	public static Date roundDateToSeconds(Date date) {
		long roundedDate = (date.getTime() / 1000) * 1000;
		return new Date(roundedDate);
	}
	
	/**
	 * Generate the corresponding backup File location given the name of the server folder.
	 * 
	 * @param fileName
	 * @return
	 */
	public static File generateBackupFileFromString(String fileName, String pathToBackups) {
		return new File(pathToBackups, fileName);
	}
	
	/**
	 * Recursively finds the Date of the most recently changed file in a directory.
	 * 
	 * @return
	 */
	public static Date lastModifiedInFolder(File file) {
		mostRecentTime = 0L;
		try {
			Files.find(file.toPath(), Integer.MAX_VALUE, (filePath, fileAttr) -> true)
					.forEach(x -> {
						if (mostRecentTime < x.toFile().lastModified())
							mostRecentTime = x.toFile().lastModified();
					});
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Exception caught while scanning a server directory for most recently modified file: ", e);
			crashProgram();
		}
		
		return new Date(mostRecentTime);
	}
	
	/**
	 * Gets the time stamp of the backup archive with the most recent time stamp in its filename.
	 */
	public static File getLatestBackup(File singleBackupDirectory) {
		// Get a list of all the files in the directory
		File[] backupList = singleBackupDirectory.listFiles(File::isFile);
		
		// Check which filename contains the most recent time stamp
		Arrays.sort(backupList);
		
		return backupList[backupList.length - 1];
	}
	
	/**
	 * Returns a Date corresponding to the time stamp in a backup archive's file name.
	 * 
	 * @param backupFile
	 * @return
	 */
	public static Date getBackupTimeStamp(File backupFile) {
		String nameOfFile = backupFile.getName();
		
		// Remove file extension
		if (nameOfFile.indexOf('.') > 0) { // NOSONAR: This is needed to handle files starting with '.'
			nameOfFile = nameOfFile.substring(0, nameOfFile.lastIndexOf('.'));
		}
		
		// Remove backup name
		nameOfFile = nameOfFile.substring(nameOfFile.length() - 19);
		
		Date date = null;
		try {
			date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").parse(nameOfFile);
		} catch (ParseException e) {
			logger.log(Level.SEVERE, "Unable to parse date format of backup archive: ", e);
			crashProgram();
		}
		
		return date;
	}
	
	/**
	 * Zips the contents of the serverFolder directory into the backupFolder directory. Appends a time stamp to the end
	 * of the zip file name indicating when the server was last modified.
	 * 
	 * @param serverFolder
	 * @param backupFolder
	 */
	public void backupSpecificServer(File serverFolder, File backupFolder) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		String zipFile = backupFolder.getAbsolutePath() + File.separator + serverFolder.getName() + "_" + sdf.format(serverMap.get(serverFolder)) + ".zip";
		
		ZipUtil.pack(serverFolder, new File(zipFile));
	}
	
	/**
	 * Returns a severe log message and exits the program.
	 */
	public static void crashProgram() {
		logger.severe("The program has encountered a problem and must stop.");
		System.exit(-1);
	}
	
}
