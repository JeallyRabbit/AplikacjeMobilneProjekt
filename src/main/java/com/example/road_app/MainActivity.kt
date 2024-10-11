// MainActivity.kt
package com.example.road_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // mapa po której porusza się użytkownik
    private lateinit var mMap: GoogleMap

    // wymagana przez api, uzywana do dostępu do lokalizacji użytkownika
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // zmienna do otrzymywania powiedomienia o aktualizacji lokalizacji
    private lateinit var locationCallback: LocationCallback

    // przycisk do dodawania wydarzen
    private lateinit var btnAddEvent: Button

    // zrzucane menu z wyborem rodzaju zdarzenia
    private lateinit var spinnerEventType: Spinner

    // lista zdarzen ktore dodał użytkownik - nie bedziemy informować go o czymś
    // o czym on nas poinformował
    private val events = mutableListOf<Event>()

    // obiekt do konwertowania obiektów JAVA'y na obiekty json i odwrotnie
    private val gson = Gson()

    // hash przechowujacy liste wydarzen i których użytkownik
    // został już poinformowany
    private val notifiedEvents = HashSet<Int>() // Track notified events

    companion object {
        // kanał którym będą wysyłanie powiadomienia
        // do użytkownika
        private const val CHANNEL_ID = "events_channel"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            // Mamy dostęp - inicjalizujemy mape
            initializeMap()
        } else {
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // Permission denied, show an appropriate message
            // You could guide the user to the app settings to enable notifications
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        btnAddEvent = findViewById(R.id.btnAddEvent)
        spinnerEventType = findViewById(R.id.spinnerEventType)

        createNotificationChannel()
        requestLocationPermissions()
        requestNotificationPermission()

        btnAddEvent.setOnClickListener {
            addEvent()
        }
    }

    private fun requestNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Dostęp już udzielony
            }
            else -> {
                // Żądanie dostępu do powiadomień
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestLocationPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Dostęp udzielony , inicjalizowanie mapy
                initializeMap()
            }
            else -> {
                // Zarządaj dostępu do lokalizacji
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Events Channel"
            val descriptionText = "Channel for nearby events notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Check for location permission
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mMap.isMyLocationEnabled = true
        mMap.uiSettings.isMyLocationButtonEnabled = true

        // Ustawienie aktualizacji lokalizacji uzytkownika co 500 ms
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 500
        ).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateMapLocation(location)
                    checkNearbyEvents(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )

        // Załadowanie zdarzeń z Json'a
        loadEvents()
    }

    private fun updateMapLocation(location: Location) {
        val userLocation = LatLng(location.latitude, location.longitude)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15f))
    }

    // dodawanie wydarzenia
    private fun addEvent() {
        //obecna lokalizacja użytkownika
        val location = fusedLocationClient.lastLocation
        location.addOnSuccessListener { loc ->
            if (loc != null) {
                // Rodzaj wydarzenia - pobrany ze zrzucanej listy - obecnie wybrany element
                val eventType = spinnerEventType.selectedItem.toString()
                //tworzenie obiektu typu evet
                val event = Event(eventType, loc.latitude, loc.longitude, System.currentTimeMillis())
                // dodanie go do listy events (z tej listy bedziemy zapisywać do json'a)
                events.add(event)
                saveEvents()
                // dodanie znacznika na mapie
                mMap.addMarker(MarkerOptions().position(LatLng(loc.latitude, loc.longitude)).title(eventType))
            }
        }
    }

    private fun saveEvents() {
        val eventsJson = gson.toJson(events)
        val file = File(filesDir, "events.json")
        file.writeText(eventsJson)
    }

    private fun loadEvents() {
        val file = File(filesDir, "events.json")
        if (file.exists()) {
            val eventsJson = file.readText()
            val eventType = object : TypeToken<List<Event>>() {}.type
            val loadedEvents: List<Event> = gson.fromJson(eventsJson, eventType)
            events.addAll(loadedEvents)

            // Dodanie znaczników
            for (event in loadedEvents) {
                mMap.addMarker(MarkerOptions().position(LatLng(event.latitude, event.longitude)).title(event.type))
            }
        }
    }

    private fun triggerNotification(event: Event) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            // Brak dostępu do powiadomień
            return
        }

        val notificationManagerCompat = NotificationManagerCompat.from(this)

        // Sprawdź czy powiadomienia są włączone
        if (!notificationManagerCompat.areNotificationsEnabled()) {
            // Powiadomienia wyłączone
            return
        }
        // tworzenie powiadomienia
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_event) // Reference to the drawable resource
            .setContentTitle("Nearby Event: ${event.type}")
            .setContentText("An event is nearby: ${event.type}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            // wyświetlanie powiadomienia
            with(notificationManagerCompat) {
                notify(event.hashCode(), builder.build())
            }
        } catch (e: SecurityException) {
           e.printStackTrace()
        }
    }
    
    // sprawdzanie jakie wydarzenia wyświetlić - bliższe niż 100 metrów
    private fun checkNearbyEvents(location: Location) {
        //lokalizacja użytkownika
        val userLocation = LatLng(location.latitude, location.longitude)

        // pętla przez wszystkie zdarzenia
        for (event in events) {
            // lokalizacja zdarzenia
            val eventLocation = LatLng(event.latitude, event.longitude)

            // dystans zdarzenia
            val distance = FloatArray(1)
            Location.distanceBetween(
                userLocation.latitude, userLocation.longitude,
                eventLocation.latitude, eventLocation.longitude,
                distance
            )
            if (distance[0] < 100) { // 100 metrow
                if (!notifiedEvents.contains(event.hashCode())) {
                    // jeżeli jest w odległości mniejszej niż 100 i nie było o nim jeszcze powiadomienia
                    triggerNotification(event)
                    notifiedEvents.add(event.hashCode())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
