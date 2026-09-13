package com.github.ananbox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.ananbox.anna.AnnaCore
import com.github.ananbox.anna.AnnaService
import com.github.ananbox.databinding.ActivityMainBinding
import com.hzy.libp7zip.P7ZipApi
import java.io.File
import java.lang.String
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val READ_REQUEST_CODE = 2
    private lateinit var mSurfaceView: SurfaceView
    private val mSurfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface
            val windowManager = windowManager
            val defaultDisplay = windowManager.defaultDisplay
            val displayMetrics = DisplayMetrics()
            defaultDisplay.getRealMetrics(displayMetrics)
            val dpi = displayMetrics.densityDpi
            Log.i(TAG, "Runtime initializing..")
            if(Anbox.initRuntime(mSurfaceView.width, mSurfaceView.height, dpi)) {
                Anbox.createSurface(surface)
                AnnaCore.attachSurface(mSurfaceView)
                Anbox.startRuntime()
                Anbox.startContainer(applicationContext.applicationInfo.nativeLibraryDir + "/libproot.so")
            }
            else {
                Anbox.createSurface(surface)
                AnnaCore.attachSurface(mSurfaceView)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(
                TAG,
                "surfaceChanged: " + mSurfaceView.width + "x" + mSurfaceView.height
            )
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
//            Renderer.removeWindow(holder.surface)
            AnnaCore.detachSurface()
            Anbox.destroySurface()
            Log.i(TAG, "surfaceDestroyed!")
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val receiver = BinderReceiver()
    private val handlerThread = HandlerThread("BinderReceiverThread")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlerThread.start()
        ContextCompat.registerReceiver(this ,receiver,
            IntentFilter("com.github.ananbox.BINDER"),
            null, Handler(handlerThread.looper),
            ContextCompat.RECEIVER_EXPORTED)

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!File(filesDir, "rootfs").exists()) {
            showRomInstaller()
            return
        }

        Anbox.setPath(filesDir.path)

        AnnaCore.init(applicationContext, File(filesDir, "rootfs"))
        if (File(filesDir, "rootfs").isDirectory) {
            BinderBridge.prepare(File(filesDir, "rootfs"))
        }

        mSurfaceView = SurfaceView(this)
        mSurfaceView.getHolder().addCallback(mSurfaceCallback)
        binding.root.addView(mSurfaceView, 0)

        // put in onResume?
        mSurfaceView.setOnTouchListener(Anbox)
        binding.fab.setOnClickListener {
            startActivity(Intent(applicationContext, SettingsActivity::class.java))
        }

        if (AnnaCore.prefs().getBoolean(AnnaCore.PREF_GATEWAY_ENABLED, false)) {
            AnnaService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1);
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AnnaCore.detachSurface()
        AnnaService.stop(this)
        Anbox.stopRuntime()
        unregisterReceiver(receiver)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == READ_REQUEST_CODE) {
            if (resultCode != Activity.RESULT_OK || data == null) {
                finishAffinity()
                return
            }
            val uri = data.data ?: return
            val progressDialog = showExtractingDialog()
            thread {
                val romFile = File(filesDir, "rootfs.7z")
                contentResolver.openInputStream(uri)?.use { input ->
                    romFile.outputStream().use { output -> input.copyTo(output) }
                }
                extractRom(romFile, progressDialog)
            }
        }
    }

    private fun showRomInstaller() {
        val asset = RomCatalog.forAbi(Build.SUPPORTED_ABIS.firstOrNull())
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rom_installer_title))
            .setMessage(
                getString(
                    if (asset != null) R.string.rom_installer_message_download
                    else R.string.rom_installer_message
                )
            )
            .setNeutralButton(R.string.rom_installer_install) { _: DialogInterface, _: Int -> pickRomFile() }
            .setNegativeButton(R.string.cancel) { _: DialogInterface, _: Int ->
                finishAffinity()
                exitProcess(0)
            }
            .setCancelable(false)
        if (asset != null) {
            builder.setPositiveButton(R.string.rom_installer_download) { _: DialogInterface, _: Int ->
                downloadRom(asset)
            }
        }
        builder.show()
    }

    private fun pickRomFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType("application/x-7z-compressed")
            },
            READ_REQUEST_CODE
        )
    }

    private fun downloadRom(asset: RomCatalog.RomAsset) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle(getString(R.string.rom_installer_downloading_title))
            setMessage(getString(R.string.rom_installer_downloading_msg))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCanceledOnTouchOutside(false)
            show()
        }
        thread {
            val target = File(filesDir, "rootfs.7z")
            val ok = RomDownloader.download(asset, target) { progress ->
                runOnUiThread {
                    if (progress.percent >= 0) {
                        progressDialog.progress = progress.percent
                    }
                }
            }
            runOnUiThread {
                progressDialog.dismiss()
                if (ok) {
                    val extractDialog = showExtractingDialog()
                    thread { extractRom(target, extractDialog) }
                } else {
                    showDownloadFailed()
                }
            }
        }
    }

    private fun extractRom(romFile: File, progressDialog: ProgressDialog) {
        val tmpDir = File(filesDir, "tmp")
        val cpu = Runtime.getRuntime().availableProcessors()
        P7ZipApi.executeCommand(
            String.format(
                Locale.US, "7z x -mmt=%d -aoa '%s' '-o%s'",
                cpu, romFile.absolutePath, filesDir
            )
        )
        BinderBridge.prepare(File(filesDir, "rootfs"))
        runOnUiThread {
            progressDialog.dismiss()
            romFile.delete()
            tmpDir.mkdir()
            recreate()
        }
    }

    private fun showExtractingDialog(): ProgressDialog = ProgressDialog(this).apply {
        setTitle(getString(R.string.rom_installer_extracting_title))
        setMessage(getString(R.string.rom_installer_extracting_msg))
        setProgressStyle(ProgressDialog.STYLE_SPINNER)
        setCanceledOnTouchOutside(false)
        show()
    }

    private fun showDownloadFailed() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rom_installer_download_failed_title))
            .setMessage(getString(R.string.rom_installer_download_failed_msg))
            .setPositiveButton(R.string.rom_installer_install) { _: DialogInterface, _: Int -> pickRomFile() }
            .setNegativeButton(R.string.cancel) { _: DialogInterface, _: Int ->
                finishAffinity()
                exitProcess(0)
            }
            .setCancelable(false)
            .show()
    }
}