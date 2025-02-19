import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.swing.*


class BackupManager(
    private val sourceFolder: File,
    private val backupFolder: File,
    private val intervalMinutes: Long,
    private val logger: (String) -> Unit = { println(it) }
) {
    private var lastBackupTime: Long = 0
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    /** Запуск службы резервного копирования */
    fun start() {
        logger("Запуск службы резервного копирования...")
        scheduler.scheduleAtFixedRate(
            { performBackup() },
            0, intervalMinutes, TimeUnit.MINUTES
        )
    }

    /** Остановка службы резервного копирования */
    fun stop() {
        scheduler.shutdown()
        logger("Служба резервного копирования остановлена.")
    }

    /** Сканирование исходной папки, выбор новых/изменённых файлов и создание архива */
    private fun performBackup() {
        try {
            val now = System.currentTimeMillis()
            logger("Сканирование папки: ${sourceFolder.absolutePath} в ${formatDate(now)}")
            val filesToBackup = mutableListOf<File>()
            sourceFolder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    // Если первый запуск или файл изменён после предыдущего резервного копирования
                    if (lastBackupTime == 0L || file.lastModified() > lastBackupTime) {
                        filesToBackup.add(file)
                    }
                }
            }
            if (filesToBackup.isNotEmpty()) {
                val archiveName = "backup_${formatDateForFilename(now)}.zip"
                val archiveFile = File(backupFolder, archiveName)
                createZipArchive(filesToBackup, archiveFile)
                logger("Резервная копия создана: ${archiveFile.absolutePath}")
            } else {
                logger("Нет новых или изменённых файлов для резервного копирования.")
            }
            lastBackupTime = now
        } catch (e: Exception) {
            e.printStackTrace()
            logger("Ошибка при создании резервной копии: ${e.message}")
        }
    }

    /** Создание ZIP-архива из списка файлов */
    private fun createZipArchive(files: List<File>, archiveFile: File) {
        ZipOutputStream(FileOutputStream(archiveFile)).use { zipOut ->
            files.forEach { file ->
                // Относительный путь файла от исходной папки
                val relativePath = file.relativeTo(sourceFolder).path
                val zipEntry = ZipEntry(relativePath)
                zipOut.putNextEntry(zipEntry)
                file.inputStream().use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }

    private fun formatDateForFilename(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
        return sdf.format(Date(timestamp))
    }

    companion object {
        /**
         * Восстанавливает файлы из архива в указанную папку.
         */
        fun restore(archiveFile: File, destinationFolder: File, logger: (String) -> Unit = { println(it) }) {
            try {
                ZipInputStream(FileInputStream(archiveFile)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        val outFile = File(destinationFolder, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zipIn.copyTo(fos)
                        }
                        logger("Восстановлен файл: ${outFile.absolutePath}")
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
                logger("Восстановление завершено в директории: ${destinationFolder.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
                logger("Ошибка при восстановлении: ${e.message}")
            }
        }
    }
}

/** Панель для управления резервным копированием */
class BackupPanel : JPanel() {
    private val sourceField = JTextField(30)
    private val backupField = JTextField(30)
    private val intervalField = JTextField("30", 5)
    private val startButton = JButton("Запустить резервное копирование")
    private val stopButton = JButton("Остановить резервное копирование")
    private val logArea = JTextArea(10, 40)
    private var backupManager: BackupManager? = null

    init {
        layout = BorderLayout()
        val inputPanel = JPanel()
        inputPanel.layout = GridBagLayout()
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        // Исходная папка
        gbc.gridx = 0; gbc.gridy = 0
        inputPanel.add(JLabel("Исходная папка:"), gbc)
        gbc.gridx = 1
        inputPanel.add(sourceField, gbc)
        gbc.gridx = 2
        val chooseSourceButton = JButton("Выбрать")
        inputPanel.add(chooseSourceButton, gbc)

        // Папка для резервного копирования
        gbc.gridx = 0; gbc.gridy = 1
        inputPanel.add(JLabel("Папка для резервного копирования:"), gbc)
        gbc.gridx = 1
        inputPanel.add(backupField, gbc)
        gbc.gridx = 2
        val chooseBackupButton = JButton("Выбрать")
        inputPanel.add(chooseBackupButton, gbc)

        // Интервал в минутах
        gbc.gridx = 0; gbc.gridy = 2
        inputPanel.add(JLabel("Интервал (минут):"), gbc)
        gbc.gridx = 1
        inputPanel.add(intervalField, gbc)

        // Кнопки запуска и остановки
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3
        val buttonPanel = JPanel()
        buttonPanel.add(startButton)
        buttonPanel.add(stopButton)
        inputPanel.add(buttonPanel, gbc)

        add(inputPanel, BorderLayout.NORTH)

        // Лог (зона для вывода сообщений)
        logArea.isEditable = false
        add(JScrollPane(logArea), BorderLayout.CENTER)

        // Обработчики кнопок выбора папок
        chooseSourceButton.addActionListener {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                sourceField.text = chooser.selectedFile.absolutePath
            }
        }
        chooseBackupButton.addActionListener {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                backupField.text = chooser.selectedFile.absolutePath
            }
        }

        startButton.addActionListener { startBackup() }
        stopButton.addActionListener { stopBackup() }
    }

    /** Функция логирования с обновлением текстового поля (в EDT) */
    private fun log(message: String) {
        SwingUtilities.invokeLater {
            logArea.append("$message\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    /** Запуск резервного копирования */
    private fun startBackup() {
        val sourcePath = sourceField.text.trim()
        val backupPath = backupField.text.trim()
        val intervalText = intervalField.text.trim()
        if (sourcePath.isEmpty() || backupPath.isEmpty() || intervalText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Заполните все поля!", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }
        val interval = intervalText.toLongOrNull()
        if (interval == null || interval <= 0) {
            JOptionPane.showMessageDialog(this, "Некорректный интервал!", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }
        val sourceFile = File(sourcePath)
        val backupFile = File(backupPath)
        if (!sourceFile.exists() || !sourceFile.isDirectory) {
            JOptionPane.showMessageDialog(this, "Исходная папка не существует или не является директорией!", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }
        if (!backupFile.exists()) {
            backupFile.mkdirs()
        }
        backupManager = BackupManager(sourceFile, backupFile, interval, ::log)
        backupManager?.start()
        log("Служба резервного копирования запущена.")
        startButton.isEnabled = false
    }

    /** Остановка резервного копирования */
    private fun stopBackup() {
        backupManager?.stop()
        backupManager = null
        log("Служба резервного копирования остановлена.")
        startButton.isEnabled = true
    }
}

/** Панель для восстановления из архива */
class RestorePanel : JPanel() {
    private val archiveField = JTextField(30)
    private val destinationField = JTextField(30)
    private val restoreButton = JButton("Восстановить")
    private val logArea = JTextArea(10, 40)

    init {
        layout = BorderLayout()
        val inputPanel = JPanel()
        inputPanel.layout = GridBagLayout()
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        // Выбор архива
        gbc.gridx = 0; gbc.gridy = 0
        inputPanel.add(JLabel("Выбрать архив:"), gbc)
        gbc.gridx = 1
        inputPanel.add(archiveField, gbc)
        gbc.gridx = 2
        val chooseArchiveButton = JButton("Выбрать")
        inputPanel.add(chooseArchiveButton, gbc)

        // Выбор папки для восстановления
        gbc.gridx = 0; gbc.gridy = 1
        inputPanel.add(JLabel("Директория для восстановления:"), gbc)
        gbc.gridx = 1
        inputPanel.add(destinationField, gbc)
        gbc.gridx = 2
        val chooseDestinationButton = JButton("Выбрать")
        inputPanel.add(chooseDestinationButton, gbc)

        // Кнопка восстановления
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3
        inputPanel.add(restoreButton, gbc)

        add(inputPanel, BorderLayout.NORTH)

        // Зона вывода сообщений
        logArea.isEditable = false
        add(JScrollPane(logArea), BorderLayout.CENTER)

        // Обработчики выбора файла/папки
        chooseArchiveButton.addActionListener {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_ONLY }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                archiveField.text = chooser.selectedFile.absolutePath
            }
        }
        chooseDestinationButton.addActionListener {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                destinationField.text = chooser.selectedFile.absolutePath
            }
        }

        restoreButton.addActionListener { restoreArchive() }
    }

    /** Функция логирования с обновлением текстового поля (в EDT) */
    private fun log(message: String) {
        SwingUtilities.invokeLater {
            logArea.append("$message\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    /** Запуск процесса восстановления в отдельном потоке */
    private fun restoreArchive() {
        val archivePath = archiveField.text.trim()
        val destinationPath = destinationField.text.trim()
        if (archivePath.isEmpty() || destinationPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Заполните все поля!", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }
        val archiveFile = File(archivePath)
        val destinationFile = File(destinationPath)
        if (!archiveFile.exists() || !archiveFile.isFile) {
            JOptionPane.showMessageDialog(this, "Архив не существует или указан неверно!", "Ошибка", JOptionPane.ERROR_MESSAGE)
            return
        }
        if (!destinationFile.exists()) {
            destinationFile.mkdirs()
        }
        // Запуск восстановления в фоновом потоке
        Thread {
            BackupManager.restore(archiveFile, destinationFile, ::log)
        }.start()
    }
}

/** Точка входа приложения – создаётся окно с двумя вкладками */
fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("Система резервного копирования")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.layout = BorderLayout()
        val tabbedPane = JTabbedPane()
        tabbedPane.addTab("Резервное копирование", BackupPanel())
        tabbedPane.addTab("Восстановление", RestorePanel())
        frame.add(tabbedPane, BorderLayout.CENTER)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
