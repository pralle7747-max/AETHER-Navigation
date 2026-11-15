package com.samsungcam.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Galerie aller Fotos auf dem Gerät. Antippen öffnet den Editor (Radieren/Verschieben/Malen). */
class GalleryActivity : AppCompatActivity() {

    private val executor = Executors.newFixedThreadPool(3)
    private val cache = LruCache<Uri, Bitmap>(200)
    private lateinit var grid: GridView

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) load() else { Toast.makeText(this, "Ohne Zugriff auf Fotos keine Galerie.", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grid = GridView(this).apply { numColumns = 3; horizontalSpacing = 4; verticalSpacing = 4; setBackgroundColor(0xFF000000.toInt()) }
        setContentView(grid)
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) load() else perm.launch(p)
    }

    override fun onResume() {
        super.onResume()
        if (grid.adapter != null) load() // neue Fotos nach dem Bearbeiten anzeigen
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun load() {
        val uris = ArrayList<Uri>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && uris.size < 2000)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
        }
        grid.adapter = Adapter(this, uris)
        grid.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, EditorActivity::class.java).setData(uris[pos]))
        }
    }

    private inner class Adapter(val ctx: Context, val items: List<Uri>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val size = parent.width / 3
            val iv = (convert as? ImageView) ?: ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListViewLayout(size)
            }
            val uri = items[pos]
            iv.tag = uri
            val hit = cache.get(uri)
            if (hit != null) { iv.setImageBitmap(hit); return iv }
            iv.setImageDrawable(null)
            executor.execute {
                val bmp = try { contentResolver.loadThumbnail(uri, Size(300, 300), null) } catch (e: Exception) { null }
                if (bmp != null) {
                    cache.put(uri, bmp)
                    iv.post { if (iv.tag == uri) iv.setImageBitmap(bmp) }
                }
            }
            return iv
        }

        private fun AbsListViewLayout(s: Int) = android.widget.AbsListView.LayoutParams(s, s)
    }
}
