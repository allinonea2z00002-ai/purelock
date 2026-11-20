package com.purelock.app.ui.location

import android.Manifest
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SafeZone
import com.purelock.app.databinding.ActivityMapBinding
import com.purelock.app.databinding.DialogZoneBinding
import com.purelock.app.location.Fix
import com.purelock.app.location.LocationHelper
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Map of safe zones (Location screen > "View map").
 *
 * WebView + MapLibre GL JS + CARTO Positron, no new dependency (MapLibre loads from CDN in the
 * WebView). Zones show as pins with radius circles — green while you are inside an auto-lock zone —
 * with a live current-location dot, tap-on-map to add a zone, a radius slider, a map / satellite /
 * OSM style switch, and a lock active / open banner. Map tiles need internet; locking itself stays
 * GPS/offline.
 */
class MapActivity : AppCompatActivity() {

    private lateinit var b: ActivityMapBinding
    private lateinit var web: WebView

    private var pageReady = false
    private val pendingJs = ArrayList<String>()

    private var zones: List<SafeZone> = emptyList()
    private var lockActive = false
    private var meFix: Fix? = null
    private var selectedId: Long? = null
    private var pendingLat: Double? = null
    private var pendingLng: Double? = null
    private var editRadius = 200
    private var satOn = false

    private val radiusChips = ArrayList<MaterialButton>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    // ---- JS bridge: called from the MapLibre map ----------------------

    inner class MapBridge {
        @JavascriptInterface
        fun onMapTap(lat: Double, lng: Double) {
            runOnUiThread {
                pendingLat = lat
                pendingLng = lng
                selectedId = null
                pushPendingJs()
                render()
            }
        }

        @JavascriptInterface
        fun onPlaceTap(id: Long) {
            runOnUiThread { selectZone(id) }
        }
    }

    // ---- lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityMapBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvBack.setOnClickListener { finish() }
        b.btnMyLocation.setOnClickListener { recenterOnMe() }
        b.btnSatellite.setOnClickListener { toggleSat() }
        b.btnAddZone.setOnClickListener {
            val la = pendingLat
            val lo = pendingLng
            if (la != null && lo != null) openAddZoneDialog(la, lo)
        }
        b.btnSaveZone.setOnClickListener { saveSelected() }
        b.btnDeleteZone.setOnClickListener { deleteSelected() }

        for ((index, r) in RADII.withIndex()) {
            val chip = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            chip.text = "$r m"
            chip.isCheckable = true
            chip.setOnClickListener { setEditRadius(r) }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index < RADII.size - 1) params.marginEnd = dp(8)
            radiusChips.add(chip)
            b.rowRadiusChips.addView(chip, params)
        }
        b.seekRadius.max = (MAX_RADIUS - MIN_RADIUS) / RADIUS_STEP
        b.seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setEditRadius(MIN_RADIUS + p * RADIUS_STEP)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        web = b.webMap
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(MapBridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                for (js in pendingJs) {
                    try {
                        web.evaluateJavascript(js, null)
                    } catch (_: Exception) {
                    }
                }
                pendingJs.clear()
                pushDataJs()
                pushMeJs()
            }
        }
        web.loadDataWithBaseURL("https://unpkg.com", mapHtml(), "text/html", "utf-8", null)

        if (!LocationHelper.hasForegroundPermission(this)) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Graph.db.safeZoneDao().observeAll().collect {
                        zones = it
                        render()
                        pushDataJs()
                    }
                }
                launch {
                    Graph.db.lockDao().observe().collect {
                        lockActive = it?.active == true
                        render()
                    }
                }
                launch {
                    LocationHelper.updates(this@MapActivity, 10_000).collect { fix ->
                        meFix = fix
                        render()
                        pushMeJs()
                        pushDataJs()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            web.onResume()
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try {
            web.onPause()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---- location + panel -------------------------------------------------

    /** Switch the MapLibre style between Positron map and Esri satellite. */
    private fun toggleSat() {
        satOn = !satOn
        b.btnSatellite.setText(if (satOn) R.string.map_map else R.string.map_satellite)
        eval("setSat($satOn);")
    }

    private fun recenterOnMe() {
        lifecycleScope.launch {
            Toast.makeText(this@MapActivity, R.string.map_finding_location, Toast.LENGTH_SHORT).show()
            val fix = LocationHelper.currentFix(this@MapActivity, highAccuracy = true)
            if (fix == null) {
                Toast.makeText(this@MapActivity, R.string.map_location_failed, Toast.LENGTH_LONG).show()
            } else {
                meFix = fix
                render()
                pushMeJs()
                pushDataJs()
                eval("recenter(${fix.latitude},${fix.longitude});")
            }
        }
    }

    /** Green circle: you are inside this zone right now (trusted fix) and the zone auto-locks. */
    private fun zoneActive(zone: SafeZone): Boolean {
        val fix = meFix ?: return false
        if (zone.autoLockMinutes <= 0) return false
        if (fix.accuracyM > MAX_ACCURACY_M) return false
        return LocationHelper.distanceMeters(fix.latitude, fix.longitude, zone.latitude, zone.longitude) <= zone.radiusM
    }

    private fun distanceTo(zone: SafeZone): Float? {
        val fix = meFix ?: return null
        return LocationHelper.distanceMeters(fix.latitude, fix.longitude, zone.latitude, zone.longitude)
    }

    private fun render() {
        if (!::b.isInitialized) return
        if (lockActive) {
            b.tvMapBanner.setText(R.string.map_lock_active)
            b.tvMapBanner.setTextColor(MaterialColors.getColor(this, R.attr.plSuccess, 0))
        } else {
            b.tvMapBanner.setText(R.string.map_lock_open)
            b.tvMapBanner.setTextColor(MaterialColors.getColor(this, R.attr.plTextSecondary, 0))
        }

        val sel = zones.find { it.id == selectedId }
        if (sel != null) {
            b.layoutEditZone.isVisible = true
            b.btnAddZone.isVisible = false
            b.tvMapInfo.text = sel.name
            val d = distanceTo(sel)
            b.tvMapDistance.text = if (d == null) {
                getString(R.string.map_location_unavailable)
            } else {
                val where = if (zoneActive(sel)) getString(R.string.map_inside_locking)
                else getString(R.string.map_outside_open)
                getString(R.string.map_m_away, d.toInt()) + " · " + where
            }
            return
        }
        b.layoutEditZone.isVisible = false
        val pla = pendingLat
        val plo = pendingLng
        if (pla != null && plo != null) {
            b.btnAddZone.isVisible = true
            b.tvMapInfo.setText(R.string.map_new_zone)
            val coords = String.format(java.util.Locale.US, "%.5f, %.5f", pla, plo)
            val fix = meFix
            b.tvMapDistance.text = if (fix == null) {
                coords + " · " + getString(R.string.map_location_unavailable)
            } else {
                val d = LocationHelper.distanceMeters(fix.latitude, fix.longitude, pla, plo)
                getString(R.string.map_m_away, d.toInt()) + " · " + coords
            }
        } else {
            b.btnAddZone.isVisible = false
            b.tvMapInfo.setText(R.string.map_tap_hint)
            b.tvMapDistance.text = if (zones.isEmpty()) {
                getString(R.string.map_no_zones)
            } else {
                getString(R.string.map_zones_saved, zones.size)
            }
        }
    }

    // ---- selection + editing ----------------------------------------------

    private fun selectZone(id: Long) {
        val zone = zones.find { it.id == id } ?: return
        selectedId = id
        pendingLat = null
        pendingLng = null
        editRadius = zone.radiusM
        syncEditControls()
        render()
        pushDataJs()
        if (editRadius > 2000) eval("fitCircle(${zone.latitude},${zone.longitude},$editRadius);")
    }

    private fun syncEditControls() {
        b.tvRadiusValue.text = getString(R.string.zone_detail_radius, editRadius)
        b.seekRadius.progress = (editRadius - MIN_RADIUS) / RADIUS_STEP
        for (i in radiusChips.indices) radiusChips[i].isChecked = RADII[i] == editRadius
    }

    private fun setEditRadius(r: Int) {
        editRadius = r.coerceIn(MIN_RADIUS, MAX_RADIUS)
        syncEditControls()
        val id = selectedId ?: return
        val zone = zones.find { it.id == id } ?: return
        eval("updateCircle($id,$editRadius,${zoneActive(zone)});")
        if (editRadius > 2000) eval("fitCircle(${zone.latitude},${zone.longitude},$editRadius);")
    }

    private fun saveSelected() {
        val id = selectedId ?: return
        lifecycleScope.launch {
            val zone = Graph.db.safeZoneDao().get(id) ?: return@launch
            Graph.db.safeZoneDao().upsert(zone.copy(radiusM = editRadius))
            syncGeofences()
            zones = zones.map { if (it.id == id) it.copy(radiusM = editRadius) else it }
            Toast.makeText(this@MapActivity, R.string.map_zone_saved, Toast.LENGTH_SHORT).show()
            render()
            pushDataJs()
        }
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        val zone = zones.find { it.id == id } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(zone.name)
            .setMessage(R.string.zone_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    Graph.db.safeZoneDao().delete(id)
                    syncGeofences()
                    selectedId = null
                    zones = zones.filter { it.id != id }
                    render()
                    pushDataJs()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Same add-zone flow as the Location screen, but centred on the tapped point. */
    private fun openAddZoneDialog(lat: Double, lng: Double) {
        val dialog = DialogZoneBinding.inflate(layoutInflater)
        dialog.etZoneRadius.setText(editRadius.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.zones_add_button)
            .setView(dialog.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = dialog.etZoneName.text?.toString().orEmpty().trim()
                val radius = dialog.etZoneRadius.text?.toString()?.toIntOrNull() ?: 200
                val auto = dialog.etZoneLock.text?.toString()?.toIntOrNull() ?: 0
                if (name.isEmpty() || radius !in MIN_RADIUS..MAX_RADIUS || auto !in 0..(7 * 24 * 60)) {
                    Toast.makeText(this, R.string.zone_invalid, Toast.LENGTH_LONG).show()
                } else {
                    lifecycleScope.launch {
                        val id = Graph.db.safeZoneDao().upsert(
                            SafeZone(name = name.take(60), latitude = lat, longitude = lng, radiusM = radius, autoLockMinutes = auto)
                        )
                        syncGeofences()
                        if (auto > 0 && !LocationHelper.hasBackgroundPermission(this@MapActivity)) {
                            Toast.makeText(this@MapActivity, R.string.zone_needs_background, Toast.LENGTH_LONG).show()
                        }
                        pendingLat = null
                        pendingLng = null
                        selectedId = id
                        editRadius = radius
                        syncEditControls()
                        Toast.makeText(this@MapActivity, R.string.map_zone_saved, Toast.LENGTH_SHORT).show()
                        render()
                        pushDataJs()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private suspend fun syncGeofences() {
        Graph.geofences.sync(Graph.db.lockDao().get(), Graph.db.safeZoneDao().getAll())
    }

    // ---- JS calls -----------------------------------------------------------

    private fun eval(js: String) {
        if (pageReady) {
            try {
                web.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        } else {
            pendingJs.add(js)
        }
    }

    private fun pushDataJs() {
        val arr = JSONArray()
        for (zone in zones) {
            val o = JSONObject()
            o.put("id", zone.id)
            o.put("name", zone.name)
            o.put("lat", zone.latitude)
            o.put("lng", zone.longitude)
            o.put("r", zone.radiusM)
            o.put("active", zoneActive(zone))
            arr.put(o)
        }
        eval("setData($arr,${selectedId ?: -1L});")
    }

    private fun pushMeJs() {
        val fix = meFix ?: return
        eval("setMe(${fix.latitude},${fix.longitude});")
    }

    private fun pushPendingJs() {
        val la = pendingLat
        val lo = pendingLng
        if (la != null && lo != null) eval("setPending($la,$lo);")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- the map page (MapLibre GL JS + CARTO Positron, no API key) --------

    private fun mapHtml(): String {
        val fix = meFix
        val first = zones.firstOrNull()
        val cLat = fix?.latitude ?: first?.latitude ?: 23.8103
        val cLng = fix?.longitude ?: first?.longitude ?: 90.4125
        return """
<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css">
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<style>html,body,#map{height:100%;margin:0;padding:0;background:#E8E8E8;}
.me-dot{width:18px;height:18px;border-radius:50%;background:#2563eb;border:3px solid #ffffff;box-shadow:0 0 0 2px rgba(37,99,235,.4);}
.style-sw{position:absolute;top:10px;right:10px;z-index:5;background:#ffffff;border-radius:8px;box-shadow:0 1px 5px rgba(0,0,0,.3);overflow:hidden;font-family:sans-serif;}
.style-sw button{display:block;width:100%;border:0;background:#fff;padding:8px 12px;font-size:13px;color:#333;text-align:left;}
.style-sw button.on{background:#2563eb;color:#fff;}</style></head>
<body><div id="map"></div>
<div class="style-sw"><button id="sw-map" class="on" onclick="setStyleMode('positron')">🗺 __MAP_LABEL__</button><button id="sw-sat" onclick="setStyleMode('satellite')">🛰 __SAT_LABEL__</button><button id="sw-osm" onclick="setStyleMode('osm')">🧭 OSM</button></div>
<script>
var POSITRON='https://basemaps.cartocdn.com/gl/positron-gl-style/style.json';
var SAT_STYLE={version:8,sources:{esri:{type:'raster',tiles:['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],tileSize:256,attribution:'Esri, Maxar, Earthstar Geographics'}},layers:[{id:'esri',type:'raster',source:'esri',minzoom:0,maxzoom:19}]};
var OSM_STYLE={version:8,sources:{osm:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap contributors'}},layers:[{id:'osm',type:'raster',source:'osm',minzoom:0,maxzoom:19}]};
var map=null;
try{ map=new maplibregl.Map({container:'map',style:POSITRON,center:[__LNG__,__LAT__],zoom:15}); }catch(e){ document.getElementById('map').innerHTML='<div style="padding:24px;font-family:sans-serif;color:#333">__WEBGL_MSG__</div>'; }
var placesData=[],selId=-1,mePos=null,pendingPos=null,pendingRadius=200,curStyle='positron';
var placeMarkers=[],meMarker=null,pendingMarker=null,lastPlaceTapMs=0;
function toRad(d){return d*Math.PI/180;}
function toDeg(r){return r*180/Math.PI;}
function circlePolygon(lat,lng,radiusM){
  var pts=[],i,b,d,lat1=toRad(lat),lng1=toRad(lng);
  for(i=0;i<=64;i++){
    b=i/64*2*Math.PI; d=radiusM/6371000;
    var lat2=Math.asin(Math.sin(lat1)*Math.cos(d)+Math.cos(lat1)*Math.sin(d)*Math.cos(b));
    var lng2=lng1+Math.atan2(Math.sin(b)*Math.sin(d)*Math.cos(lat1),Math.cos(d)-Math.sin(lat1)*Math.sin(lat2));
    pts.push([toDeg(lng2),toDeg(lat2)]);
  }
  return {type:'Polygon',coordinates:[pts]};
}
function placesGeo(){
  var feats=[];
  for(var i=0;i<placesData.length;i++){
    var p=placesData[i];
    feats.push({type:'Feature',properties:{id:p.id,color:p.active?'#22c55e':'#3b82f6'},geometry:circlePolygon(p.lat,p.lng,p.r)});
  }
  return {type:'FeatureCollection',features:feats};
}
function ensurePlaceLayers(){
  if(!map.getSource('places-circles')){
    map.addSource('places-circles',{type:'geojson',data:placesGeo()});
    map.addLayer({id:'places-fill',type:'fill',source:'places-circles',paint:{'fill-color':['get','color'],'fill-opacity':0.15}});
    map.addLayer({id:'places-line',type:'line',source:'places-circles',paint:{'line-color':['get','color'],'line-width':['case',['==',['get','id'],selId],4,2]}});
  }else{
    map.getSource('places-circles').setData(placesGeo());
    if(map.getLayer('places-line')) map.setPaintProperty('places-line','line-width',['case',['==',['get','id'],selId],4,2]);
  }
}
function ensurePendingLayer(){
  if(!pendingPos) return;
  var data={type:'FeatureCollection',features:[{type:'Feature',properties:{},geometry:circlePolygon(pendingPos[0],pendingPos[1],pendingRadius)}]};
  if(!map.getSource('pending-circle')){
    map.addSource('pending-circle',{type:'geojson',data:data});
    map.addLayer({id:'pending-fill',type:'fill',source:'pending-circle',paint:{'fill-color':'#FFB84D','fill-opacity':0.15}});
    map.addLayer({id:'pending-line',type:'line',source:'pending-circle',paint:{'line-color':'#FFB84D','line-width':2}});
  }else map.getSource('pending-circle').setData(data);
}
function renderAll(){
  if(!map||!map.isStyleLoaded()) return;
  ensurePlaceLayers();
  ensurePendingLayer();
  for(var i=0;i<placeMarkers.length;i++) placeMarkers[i].remove();
  placeMarkers=[];
  for(var j=0;j<placesData.length;j++){
    (function(p){
      var col=p.active?'#22c55e':'#3b82f6';
      var m=new maplibregl.Marker({color:col}).setLngLat([p.lng,p.lat]).addTo(map);
      m.getElement().addEventListener('click',function(ev){ev.stopPropagation();placeTap(p.id);});
      placeMarkers.push(m);
    })(placesData[j]);
  }
  if(pendingPos){
    if(pendingMarker) pendingMarker.setLngLat([pendingPos[1],pendingPos[0]]);
    else pendingMarker=new maplibregl.Marker({color:'#f59e0b'}).setLngLat([pendingPos[1],pendingPos[0]]).addTo(map);
  }
  if(mePos){
    if(meMarker) meMarker.setLngLat([mePos[1],mePos[0]]);
    else{var el=document.createElement('div');el.className='me-dot';meMarker=new maplibregl.Marker({element:el}).setLngLat([mePos[1],mePos[0]]).addTo(map);}
  }
}
function placeTap(id){lastPlaceTapMs=Date.now();Android.onPlaceTap(id);}
function setData(json,sid){placesData=json;selId=sid;renderAll();}
function setMe(lat,lng){mePos=[lat,lng];renderAll();}
function setPending(lat,lng){pendingPos=[lat,lng];renderAll();}
function updateCircle(id,radius,active){
  for(var i=0;i<placesData.length;i++){if(placesData[i].id===id){placesData[i].r=radius;placesData[i].active=active;}}
  pendingRadius=radius;renderAll();
}
function recenter(lat,lng){if(map) map.flyTo({center:[lng,lat],zoom:16});}
function fitCircle(lat,lng,radius){
  if(!map) return;
  var dLat=radius/111320,dLng=radius/(111320*Math.cos(toRad(lat)));
  map.fitBounds([[lng-dLng,lat-dLat],[lng+dLng,lat+dLat]],{padding:30});
}
function setStyleMode(mode){
  curStyle=mode;
  var b1=document.getElementById('sw-map'),b2=document.getElementById('sw-sat'),b3=document.getElementById('sw-osm');
  if(b1)b1.className=(mode==='positron')?'on':'';
  if(b2)b2.className=(mode==='satellite')?'on':'';
  if(b3)b3.className=(mode==='osm')?'on':'';
  if(!map) return;
  if(mode==='satellite') map.setStyle(SAT_STYLE);
  else if(mode==='osm') map.setStyle(OSM_STYLE);
  else map.setStyle(POSITRON);
}
function setSat(on){setStyleMode(on?'satellite':'positron');}
if(map){
  map.on('load',function(){renderAll();});
  map.on('style.load',function(){renderAll();});
  map.on('click',function(e){
    if(Date.now()-lastPlaceTapMs<500)return;
    var feats=[];
    try{if(map.getLayer('places-fill')) feats=map.queryRenderedFeatures(e.point,{layers:['places-fill']});}catch(err){}
    if(feats&&feats.length>0){placeTap(feats[0].properties.id);return;}
    Android.onMapTap(e.lngLat.lat,e.lngLat.lng);
  });
}
</script></body></html>
""".replace("__LAT__", cLat.toString()).replace("__LNG__", cLng.toString())
            .replace("__MAP_LABEL__", getString(R.string.map_map))
            .replace("__SAT_LABEL__", getString(R.string.map_satellite))
            .replace("__WEBGL_MSG__", getString(R.string.map_webgl_missing))
    }

    companion object {
        private val RADII = intArrayOf(100, 200, 500, 1000, 2000, 5000)
        private const val MIN_RADIUS = 100
        private const val MAX_RADIUS = 5000
        private const val RADIUS_STEP = 50
        private const val MAX_ACCURACY_M = 200f
    }
}
