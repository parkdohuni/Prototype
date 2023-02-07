package com.saloris.prototype

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.AnimationDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.solutioncore.CameraInput
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import com.saloris.prototype.databinding.ActivityMainBinding
import com.saloris.prototype.databinding.ActivityStateBinding
import com.saloris.prototype.util.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.concurrent.timer
import kotlin.math.pow
import kotlin.math.sqrt

class StateActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    /* Toast */
    private val toast = MakeToast()

    /* Tutorial */
    private fun isFirst(): Boolean {
        val firstPref = getSharedPreferences("isFirst", Activity.MODE_PRIVATE)
        return firstPref.getBoolean("state", true)
    }

    private fun setFirstFalse() {
        val firstPref = getSharedPreferences("isFirst", Activity.MODE_PRIVATE)
        val firstEdit = firstPref.edit()
        firstEdit.putBoolean("state", false)
        firstEdit.apply()
    }

    /* Permission */
    private val locationPermissionList = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @RequiresApi(Build.VERSION_CODES.S)
    private val bluetoothPermissionList = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val cameraPermissionList = arrayOf(
        Manifest.permission.CAMERA
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val deniedList = result.filter { !it.value }.map { it.key }
            Log.d("State", "$deniedList")
            if (deniedList.isNotEmpty()) {
                if (deniedList.any { it == Manifest.permission.CAMERA }) {
                    println("any1: $isCameraOn")
                    isCameraOn = false
                    println("any2: $isCameraOn")
                }

//                AlertDialog.Builder(this)
//                    .setTitle("알림")
//                    .setMessage("권한이 거부되었습니다. 사용을 원하시면 설정에서 해당 권한을 직접 허용하셔야 합니다.")
//                    .setPositiveButton("설정") { _, _ -> openAndroidSetting() }
//                    .setNegativeButton("취소", null)
//                    .create()
//                    .show()
            } else {
                isCameraOn = true
            }
        }

    private fun openAndroidSetting() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            addCategory(Intent.CATEGORY_DEFAULT)
            data = Uri.parse("package:${packageName}")
        }
        startActivity(intent)
    }

    /* BLE */
    private var isHeartRateValid = false
    private var hr = 0
    private var time = 0

    private lateinit var name: String
    private lateinit var address: String
    private var device: BluetoothDevice? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager =
            this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var isBluetoothOn = false
    private var bluetoothGatt: BluetoothGatt? = null
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (status == BluetoothGatt.GATT_FAILURE) {
                disconnectGatt()
                return
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectGatt()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                /* Permission */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            baseContext, Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissionLauncher.launch(bluetoothPermissionList)
                        return
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(
                            baseContext, Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissionLauncher.launch(locationPermissionList)
                        return
                    }
                }

                isBluetoothOn = true
                timerTask.cancel()
                if (!isCameraOn) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.state.visibility = View.VISIBLE
                        binding.stateFitting.visibility = View.VISIBLE
                        binding.stateFitting2.visibility = View.VISIBLE
                    }
                }
                Log.d("State", "Connected to the GATT server")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isBluetoothOn = false
                isHeartRateValid = false
                toNewTimerTask()
//                timer.schedule(timerTask, SCAN_TIME)
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.hrState.visibility = View.INVISIBLE
//                    binding.deviceState.visibility = View.VISIBLE
//                    binding.textDeviceState.text = getString(R.string.connecting)
//                    binding.hrStateFitting.visibility = View.INVISIBLE
                    binding.timerStateFitting.visibility = View.INVISIBLE
                    binding.deviceStateFitting.visibility = View.VISIBLE
//                    binding.textDeviceStateFitting.text = getString(R.string.connecting_inline)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("State", "Device service discovery failed, status: $status")
                return
            }
            Log.d("State", "Services discovery is successful")

            if (gatt == null) {
                Log.e("State", "Unable to find gatt")
                return
            }


            /* Permission */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        baseContext, Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(bluetoothPermissionList)
                    return
                }
            } else {
                if (ActivityCompat.checkSelfPermission(
                        baseContext, Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(locationPermissionList)
                    return
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
//            Log.d("State", "characteristic changed: " + characteristic.uuid.toString())
            readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("State", "Characteristic read successfully")
                readCharacteristic(characteristic)
            } else {
                Log.e("State", "Characteristic read unsuccessful, status: $status")
            }
        }

        private fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
            hr = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
            Log.d("HR", "$hr")
            println(isBluetoothOn)
            if (hr == 0) {
                isHeartRateValid = false
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.hrState.visibility = View.INVISIBLE
//                    binding.hrStateFitting.visibility = View.INVISIBLE
                    binding.timerStateFitting.visibility = View.INVISIBLE
//                    binding.deviceState.visibility = View.VISIBLE
                    binding.deviceStateFitting.visibility = View.VISIBLE
//                    binding.textDeviceState.text = getString(R.string.waiting)
//                    binding.textDeviceStateFitting.text = getString(R.string.waiting)
                }
            } else {
                isHeartRateValid = true
                displayHeartRate(hr)
            }
        }
    }

    private fun connectGatt() {
        /* Permission */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    baseContext, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(bluetoothPermissionList)
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    baseContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(locationPermissionList)
                return
            }
        }

    }

    private fun disconnectGatt() {
        isBluetoothOn = false

        /* Permission */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    baseContext, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    baseContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        if (bluetoothAdapter!!.isEnabled) {
            Log.d("State", "Disconnecting Gatt connection")

        }
    }

    private fun displayHeartRate(hr: Int) {
        val hrString = hr.toString()

        lifecycleScope.launch(Dispatchers.Main) {
//            binding.deviceState.visibility = View.GONE
            binding.deviceStateFitting.visibility = View.GONE
            binding.hrState.visibility = View.VISIBLE
//            binding.hrStateFitting.visibility = View.VISIBLE
            binding.timerStateFitting.visibility = View.VISIBLE
//            binding.heartRate.text = hrString
//            binding.heartRateFitting.text = hrString
//
//            binding.heartRate.text = hrString
//            chart.addEntry(hr)
        }
    }

    /* FaceMesh */
    private var isCameraOn = true

    private var isFaceOn = false
    private var isFaceGood = false
    private var isFaceValid = false

    private lateinit var prefs: SharedPreferences
    private lateinit var faceMeshSettings: BooleanArray
    private lateinit var faceMeshColors: ArrayList<FloatArray>
    private var alarmState: Boolean = false

    private lateinit var faceMesh: FaceMesh

    private lateinit var cameraInput: CameraInput
    private lateinit var glSurfaceView: SolutionGlSurfaceView<FaceMeshResult>

    private var count: Int = 0          // 총 블링크 카운트
    private var blink: Int = 0          // 현재 눈 감은상태
    private var totalBlink: Int = 0     // 누적 눈 깜빡임
    private var longClosedCount: Int = 0// 3초 이상 눈 감은 카운트
    private var longClosedEye: Int = 0  // 3초 이상 눈 감은 누적 횟수
    private var longClosedState: Boolean = false // 3초 이상 눈 감은 상태

    //private var afterState: Boolean = false // 두 번째 3초 이상 눈감은 상태 확인
    private var face: String = ""       // 현재 얼굴방향
    private var leftEye: String = ""    // 왼쪽 눈 방향
    private var rightEye: String = ""   // 오른쪽 눈 방향
    private var ear: Float = 0.0f
    private var mar: Float = 0.0f
    private var moe: Float = 0.0f

    private fun colorLoad(value: Int): FloatArray {
        return when (value) {
            1 -> WHITE_COLOR
            2 -> ORANGE_COLOR
            3 -> BLUE_COLOR
            4 -> RED_COLOR
            5 -> GREEN_COLOR

            else -> BLACK_COLOR
        }
    }

    private fun initFaceMesh() {
        // Initializes a new MediaPipe Face Mesh solution instance in the streaming mode.
        // refineLandmark - 눈, 입술 주변으로 분석 추가.
        faceMesh = FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(false)
                .setRefineLandmarks(true)
                .setRunOnGpu(true)
                .build()
        )
        faceMesh.setErrorListener { message: String, _: RuntimeException? ->
            Log.e("State", "MediaPipe Face Mesh error:$message")
            toast.makeToast(this, "인식이 되지 않습니다.")
        }
    }

    private var fittingLevel = 0
    private var timerCheck = true

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initGlSurfaceView() {
        // Initializes a new Gl surface view with a user-defined FaceMeshResultGlRenderer.
        glSurfaceView = SolutionGlSurfaceView(this, faceMesh.glContext, faceMesh.glMajorVersion)
        glSurfaceView.setSolutionResultRenderer(
            FaceMeshResultGlRenderer(faceMeshSettings, faceMeshColors)
        )
        faceMesh.setResultListener { faceMeshResult: FaceMeshResult? ->
            checkLandmark(faceMeshResult)

            lifecycleScope.launch(Dispatchers.Main) {
                if (isFaceValid) {
//                    with(binding.guideline) {
//                        if (this@StateActivity::guidelineAnimation.isInitialized)
//                            guidelineAnimation.stop()
//                        setBackgroundResource(R.drawable.face_guideline_complete)
//                    }
                    binding.faceFittingWarningText.text =
                        getString(R.string.face_fitting_complete)
                    // 인식 완료 1초 후 화면 변경
                    if (timerCheck) {
                        timer2 = timer(period = 100) {
                            timerCheck = false
                            fittingLevel += 1
                            Log.d("fittingLevel", "$fittingLevel")
                            if (fittingLevel >= MAX_FITTING_LEVEL) {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    binding.stateFitting.visibility = View.INVISIBLE
                                    binding.stateFitting2.visibility = View.VISIBLE
                                    binding.faceFittingWarningText.visibility = View.INVISIBLE
                                    binding.guideline.visibility = View.INVISIBLE
                                    if (isBluetoothOn) {
                                        binding.state.visibility = View.VISIBLE
                                    }
                                }
                                cancel()
                            }
                        }
                    }
                } else {
                    reset()
                    binding.faceFitting.visibility = View.VISIBLE
                    binding.state.visibility = View.GONE
                    if (isBluetoothOn) {
                        binding.stateFitting.visibility = View.VISIBLE
                    }
//                    with(binding.guideline) {
//                        setBackgroundResource(R.drawable.face_guideline)
//                        guidelineAnimation = background as AnimationDrawable
//                        guidelineAnimation.start()
//                    }
                    if (!isFaceOn) {
                        binding.faceFittingWarningText.text = getString(R.string.face_fitting_init)
                    }
                    if (!isFaceGood) {
                        binding.faceFittingWarningText.text = getString(R.string.face_fitting_warn)
                    }
                }
            }

            glSurfaceView.setRenderData(faceMeshResult, true)
            glSurfaceView.requestRender()
        }
    }

    private fun postGlSurfaceView() {
        cameraInput = CameraInput(this)
        cameraInput.setNewFrameListener { faceMesh.send(it) }

        glSurfaceView.post { startCamera() }
        glSurfaceView.visibility = View.VISIBLE
    }

    private fun startCamera() {
        println("startCamera!!!!!!!!!!!!!!!")
        cameraInput.start(this, faceMesh.glContext, CameraInput.CameraFacing.FRONT, 480, 640)
    }

    /* date 시간 구하기 */
    private var startTime: Long = 0
    private var beforeTime: Long = 0
    private var startCheck: Boolean = true
    private var beforeCheck: Boolean = true

    private fun getTime(): Long {
        var now = System.currentTimeMillis()
        //var date = Date(now)

        //var dateFormat = SimpleDateFormat("yyyy-MM-dd")
        //var getTime = dateFormat.format(date)

        return now
    }

    //시간 차 구하기
    private fun betweenTime(before: Long): Long {
        //var nowFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(getTime())
        //var beforeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(before)
        var now = System.currentTimeMillis()
        var diffSec = (now - before) / 1000

        return diffSec
    }

    // landmark 분석
    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkLandmark(result: FaceMeshResult?) {
        if (result == null || result.multiFaceLandmarks().isEmpty()) {
            isFaceOn = false
            isFaceGood = true
            isFaceValid = false

            return
        }

        with(result.multiFaceLandmarks()[0].landmarkList) {
            val mouth = MOUTH_INDEX.map { get(it) }
            val lEye = LEFT_EYE_INDEX.map { get(it) }
            val rEye = RIGHT_EYE_INDEX.map { get(it) }
            val base = BASE_INDEX.map { get(it) }
            val pitching = FACE_PITCHING.map { get(it) }

            // 얼굴이 가이드라인 안에 있는지 0.10 0.95 0.26 0.86
            isFaceOn =
                !(pitching[0].y < 0.08 || pitching[1].y > 0.97 || pitching[2].x < 0.24 || pitching[3].x > 0.88)

            val nose = result.multiFaceLandmarks()[0].getLandmark(4).z
            isFaceGood = -0.14 < nose && nose < -0.04

            isFaceValid = isFaceOn && isFaceGood
            if (!isFaceValid) return

            //(1) ear : EAR은 EAR(Eye Aspect Ratio) 알고리즘을 이용해 나타낸 비율이며 사용자가 현재 눈을 감고 있는지 파악하기 위해 사용되고
            // 기본적으로 EAR 계산은 다음과 같은 방법으로 진행.

            val leftEAR = ((distance(lEye[1].x, lEye[5].x, lEye[1].y, lEye[5].y))
                    + (distance(lEye[2].x, lEye[4].x, lEye[2].y, lEye[4].y))) /
                    (2 * (distance(lEye[0].x, lEye[3].x, lEye[0].y, lEye[3].y)))

            val rightEAR = ((distance(rEye[1].x, rEye[5].x, rEye[1].y, rEye[5].y))
                    + (distance(rEye[2].x, rEye[4].x, rEye[2].y, rEye[4].y))) /
                    (2 * (distance(rEye[0].x, rEye[3].x, rEye[0].y, rEye[3].y)))

            ear = (leftEAR + rightEAR) / 2
            //눈 밑 두 점과 눈 위 두 점의 각각의 사이 거리를 더한 후 눈의 외안각과 내안각 사이 거리를 나누어주면 눈의 감김 비율이 나오게 되어
            // 이를 바탕으로 현재 눈의 상태를 판단한 후 눈의 landmark를 추출하게 되면 오른쪽 눈과 왼쪽 눈의 해당하는 점들의 정보를 받을 수 있음.
            mar = ((distance(mouth[1].x, mouth[7].x, mouth[1].y, mouth[7].y))
                    + (distance(mouth[2].x, mouth[6].x, mouth[2].y, mouth[6].y))
                    + (distance(mouth[3].x, mouth[5].x, mouth[3].y, mouth[5].y))) /
                    (2 * (distance(mouth[0].x, mouth[4].x, mouth[0].y, mouth[4].y)))
            moe = mar / ear

            // 좌, 우 눈 길이
            val leftEyeDistance = distance(lEye[6].x, base[1].x, lEye[6].y, base[1].y).pow(2)
            val rightEyeDistance = distance(rEye[6].x, base[1].x, rEye[6].y, base[1].y).pow(2)

            // 얼굴 방향 측정
            face = faceDirection(mouth[8].z, mouth[9].z, lEye[0].z, rEye[3].z, base[0].z, base[2].z)

            // 한 눈을 감았을 떄 EAR 평균 0.14
            //ear의 값이 작을수록 눈을 감은 상태라고 판단
            if (ear < 0.09) {
                if (!longClosedState) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Log.d("blink", "blink")
                        binding.isBlink.text = "눈을 감음"
                        with(binding.blink) {
                            text = getString(R.string.blink)
                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.drowsy))
                            visibility = View.INVISIBLE
                        }
                    }

                }
                count++//눈을 감았으니 카운트
                blink = 0 //눈을 감으면 0
                leftEye = getString(R.string.blink)
                rightEye = getString(R.string.blink)
                if (beforeCheck) {
                    beforeTime = getTime()
                    beforeCheck = false
                }
                if (startCheck) {
                    startTime = getTime()
                    startCheck = false
                }
//                if (longClosedCount == 1 && (betweenTime(startTime) % 1) == 0L) {
//                    lifecycleScope.launch(Dispatchers.Main) {
//                        with(binding.timerFitting) {
//                            text = (15 - betweenTime(startTime)).toString()
//                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.white))
//                            visibility = View.VISIBLE
//                        }
//                        with(binding.longClosedFitting) {
//                            text = longClosedCount.toString()
//                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.white))
//                            visibility = View.VISIBLE
//                        }
//                    }
//                    if (betweenTime(startTime) <= 0) {
//                        lifecycleScope.launch(Dispatchers.Main) {
//                            with(binding.timerFitting) {
//                                text = 0.toString()
//                                setTextColor(ContextCompat.getColor(this@StateActivity,
//                                    R.color.white))
//                                visibility = View.VISIBLE
//                            }
//                            with(binding.longClosedFitting) {
//                                text = 0.toString()
//                                setTextColor(ContextCompat.getColor(this@StateActivity,
//                                    R.color.white))
//                                visibility = View.VISIBLE
//                            }
//                        }
//                    }
//                }
//                if (!longClosedState) {
//                    if ((betweenTime(beforeTime) % 1) == 0L && betweenTime(beforeTime) != 0L) {
//                        lifecycleScope.launch(Dispatchers.Main) {
//                            with(binding.blink) {
//                                text = betweenTime(beforeTime).toString()
//                                setTextColor(ContextCompat.getColor(this@StateActivity,
//                                    R.color.drowsy))
//                                visibility = View.INVISIBLE
//                            }
//                            with(binding.longClosedFitting) {
//                                text = longClosedCount!!.toString()
//                                setTextColor(ContextCompat.getColor(this@MainActivity,
//                                    R.color.white))
//                                visibility = View.VISIBLE
//                            }
//                        }
//                    }
//                }
                //눈을 3초 이상 감으면
                if (betweenTime(beforeTime) >= 3) {
                    longClosedCount++
                    longClosedEye++
                    longClosedState = true
                    beforeCheck = true
                    lifecycleScope.launch(Dispatchers.Main) {
                        with(binding.blink) {
                            text = getString(R.string.long_closed_eye)
                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.red))
                            visibility = View.INVISIBLE
                        }
                        with(binding.longClosedFitting) {
                            text = longClosedCount.toString()
                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.white))
                            visibility = View.VISIBLE
                        }
                    }
                    if (longClosedCount == 0) {
                        startTime = getTime()
                    }
                    if (betweenTime(startTime) > 15) {
                        longClosedCount = 1
                        //afterState = false
                        startTime = getTime()
                    } else if (longClosedCount >= 2 && betweenTime(startTime) <= 15) {
                        if (alarmState) {
                            startWarningOn()
                        } else {
                            startWarningOff()
                        }
                        longClosedCount = 1
                        //afterState = false
                        startTime = getTime()
                    }
                }
            } else { //눈을 뜬 상태
                if (count > 2 || ear > 0.09) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        with(binding.blink) {
                            binding.isBlink.text = "눈을 뜸"
                            text = getString(R.string.blink)
                            setTextColor(ContextCompat.getColor(this@StateActivity, R.color.drowsy))
                            visibility = View.GONE
                        }
                    }
//                    if (longClosedCount == 1 && (betweenTime(startTime) % 1) == 0L) {
//                        lifecycleScope.launch(Dispatchers.Main) {
//                            with(binding.timerFitting) {
//                                text = (15 - betweenTime(startTime)).toString()
//                                setTextColor(ContextCompat.getColor(this@StateActivity,
//                                    R.color.white))
//                                visibility = View.VISIBLE
//                            }
//                            with(binding.longClosedFitting) {
//                                text = longClosedCount.toString()
//                                setTextColor(ContextCompat.getColor(this@StateActivity,
//                                    R.color.white))
//                                visibility = View.VISIBLE
//                            }
//                        }
//                    }
                    count = 0
                    blink = 1
                    totalBlink++
                    beforeCheck = true
                    //startCheck = false
//                    if(longClosedCount == 0 || afterState == true) {
//                        startCheck = true

//                    }
                    longClosedState = false
                    stopWarning()
                }
                //(1) leftEye, rightEye : 왼쪽과 오른쪽 눈을 구분하여 각각의 동공이 어느 방향을 바라보는지 나타냄.
                if (leftEAR < 0.22) {
                    leftEye = getString(R.string.blink)
                    rightEye = eyeDirection(leftEyeDistance, rightEyeDistance)
                } else if (rightEAR < 0.22) {
                    leftEye = eyeDirection(leftEyeDistance, rightEyeDistance)
                    rightEye = getString(R.string.blink)
                } else {
                    leftEye = eyeDirection(leftEyeDistance, rightEyeDistance)
                    rightEye = eyeDirection(leftEyeDistance, rightEyeDistance)
                }
            }
        }
    }

    private fun distance(rx: Float, lx: Float, ry: Float, ly: Float): Float {
        return sqrt((rx - lx).pow(2) + (ry - ly).pow(2))
    }

    private fun eyeDirection(ld: Float, rd: Float): String {
        return if ((ld - rd) > 0.004)
            getString(R.string.left)
        else if ((ld - rd) < -0.0035)
            getString(R.string.right)
        else
            getString(R.string.front)

    }

    private fun faceDirection(
        lez: Float, rez: Float, lmz: Float, rmz: Float, hp: Float, cp: Float,
    ): String {
        val fdRatio = (lez + lmz) - (rez + rmz)

        return if (hp - cp < -0.05)
            getString(R.string.down)
        else {
            if (fdRatio > 0.15)
                getString(R.string.left)
            else if (fdRatio < -0.15)
                getString(R.string.right)
            else
                getString(R.string.front)
        }
    }
    // face mesh와 상관없는 코드
    /* Warn */
    private val toneGenerator1 = ToneGenerator(AudioManager.STREAM_MUSIC, 200)
    private val toneGenerator2 = ToneGenerator(AudioManager.STREAM_MUSIC, 500)

    private var warningLevel = 0
    private var standard = 100

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startWarningOn() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        //vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        toneGenerator1.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)

        lifecycleScope.launch(Dispatchers.Main) {
//            with(binding.drowsiness) {
//                text = getString(R.string.drowsy)
//                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
//            }
//            with(binding.drowsinessFitting) {
//                text = getString(R.string.drowsy)
//                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
//            }
            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startWarningOff() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        //vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        //toneGenerator1.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)

        lifecycleScope.launch(Dispatchers.Main) {
//            with(binding.drowsiness) {
//                text = getString(R.string.drowsy)
//          w      setTextColor(ContextCompat.getColor(this@StateActivity, R.color.red))
//            }
//            with(binding.drowsinessFitting) {
//                text = getString(R.string.drowsy)
//                setTextColor(ContextCompat.getColor(this@StateActivity, R.color.red))
//            }
            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun continueWarning() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
//        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        toneGenerator2.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)

        lifecycleScope.launch(Dispatchers.Main) {
//            with(binding.drowsiness) {
//                text = getString(R.string.sleep)
//                setTextColor(ContextCompat.getColor(this@StateActivity, R.color.red))
//            }
//            with(binding.drowsinessFitting) {
//                text = getString(R.string.sleep)
//                setTextColor(ContextCompat.getColor(this@StateActivity, R.color.red))
//            }
            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
            }
        }
    }

    private fun stopWarning() {
//        toneGenerator1.stopTone()

        lifecycleScope.launch(Dispatchers.Main) {
//            with(binding.drowsiness) {
//                text = getString(R.string.normal)
//                setTextColor(ContextCompat.getColor(this@StateActivity, R.color.white))
//            }
//            with(binding.drowsinessFitting) {
//                text = getString(R.string.normal)
//                setTextColor(ContextCompat.getColor(this@StateActivity, R.color.white))
//            }
            with(binding.warningFilter) {
                visibility = View.GONE
                (drawable as AnimationDrawable).stop()
            }
        }
    }

    /* Save (influxDB) */
    private fun saveHeartRate() {
//        val dao = HeartRateDao()
//        val heartRate = HeartRate(uid, hr)

//        lifecycleScope.launch(Dispatchers.IO) {
//            val isInserted = async { dao.insert(heartRate) }
//            withContext(Dispatchers.Main) {
//                if (isInserted.await()) {
//                    binding.networkWarning.visibility = View.GONE
//                    binding.networkWarningText.visibility = View.GONE
//                } else {
//                    binding.networkWarning.visibility = View.VISIBLE
//                }
//            }
//        }
    }

    private fun saveDriverState() {
//        val dao = DriverStateDao()
//        val driverState =
//            DriverState(uid,
//                blink,
//                totalBlink,
//                longClosedEye,
//                rightEye,
//                leftEye,
//                ear,
//                mar,
//                moe,
//                face)

//        lifecycleScope.launch(Dispatchers.IO) {
//            val isInserted = async { dao.insert(driverState) }
//            withContext(Dispatchers.Main) {
//                if (isInserted.await()) {
//                    binding.networkWarning.visibility = View.GONE
//                    binding.networkWarningText.visibility = View.GONE
//                } else {
//                    binding.networkWarning.visibility = View.VISIBLE
//                }
//            }
//        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveAndWarn() {
        // Save
//        if (isFaceValid) saveDriverState()
//        if (isHeartRateValid) saveHeartRate()

        // Warn
        if (isHeartRateValid) {
            Log.d("State", "Warning Level: $warningLevel")
            if (hr > standard * 0.85 && hr < standard * 0.90) {
                Log.d("State", "심박수 경고")
                //startWarning()
            } else if (hr <= standard * 0.85) {
                Log.d("State", "심박수 위험")
                //continueWarning()
            } else {
                //stopWarning()
            }
        }
    }

    /* View */
    private val timer = Timer(true)
    private lateinit var timerTask: TimerTask

    private fun toNewTimerTask() {
        timerTask = object : TimerTask() {
            override fun run() {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.state.visibility = View.GONE
                    binding.stateFitting.visibility = View.INVISIBLE
                    binding.stateFitting2.visibility = View.INVISIBLE
                }
            }
        }
    }

//    private lateinit var guidelineAnimation: AnimationDrawable

    private lateinit var binding: ActivityStateBinding

    private var timer2 = Timer(true)

    private fun reset() {
        timer2.cancel()
        timerCheck = true
        fittingLevel = 0
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* View */
        binding = ActivityStateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /* Status Bar & Navigation Bar */
        val barColor = ContextCompat.getColor(this, R.color.black)
        with(window) {
            statusBarColor = barColor
            navigationBarColor = barColor
        }
        with(WindowInsetsControllerCompat(window, window.decorView)) {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        /* Tutorial */
        if (isFirst()) {
            // Tutorial
            println("Welcome to Tutorial!") // TODO: 튜토리얼 화면 구현
            setFirstFalse()
        }


        // 화면 항상 켜짐
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        name = intent.getStringExtra("name") ?: "noname"
        address = intent.getStringExtra("address") ?: ""


//        /* Permission */
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            permissionList = permissionList.plus(bluetoothPermissionList)
//        }
//        requestPermissionLauncher.launch(permissionList)
        // 심박수 정보 표시창 타이머
        toNewTimerTask()

        /* */
        binding.networkWarningIcon.setOnClickListener {
            binding.networkWarningText.visibility =
                if (binding.networkWarningText.visibility == View.GONE) View.VISIBLE
                else View.GONE
//            Timer().schedule(
//                object : TimerTask() {
//                    override fun run() {
//                        binding.networkWarningText.visibility = View.GONE
//                    }
//                }, 0, 3000
//            )
        }

        binding.networkWarningText.setOnClickListener { it.visibility = View.GONE }

        /* BLE */
        if (bluetoothAdapter == null) {
            binding.state.visibility = View.GONE
            binding.stateFitting.visibility = View.INVISIBLE
            binding.stateFitting2.visibility = View.INVISIBLE
            toast.makeToast(this, "블루투스를 지원하지 않습니다.")
        } else {
            if (bluetoothAdapter!!.isEnabled) {
                if (address.isNotEmpty()) {
                    device = bluetoothAdapter!!.getRemoteDevice(address)
                    isBluetoothOn = true
                }
                Log.d("Device", "$device")
            } else {
                binding.state.visibility = View.GONE
                binding.stateFitting.visibility = View.INVISIBLE
                binding.stateFitting2.visibility = View.INVISIBLE
                toast.makeToast(this, "블루투스가 꺼져 있습니다.")
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val bluetoothChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                    )
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            Log.i("State", "Bluetooth off")
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            Log.i("State", "Turning Bluetooth off...")

                            binding.warningFilter.visibility = View.GONE
                            binding.state.visibility = View.GONE
                            binding.stateFitting.visibility = View.INVISIBLE
                            binding.stateFitting2.visibility = View.INVISIBLE
                        }
                        BluetoothAdapter.STATE_ON -> {
                            Log.i("State", "Bluetooth on")

                            if (address.isNotEmpty()) {
                                device = bluetoothAdapter!!.getRemoteDevice(address)
                            }
                            Log.d("Device", "$device")

                            disconnectGatt()
                            connectGatt()
                        }
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            Log.i("State", "Turning Bluetooth on...")

                            if (!isCameraOn) {
                                binding.state.visibility = View.VISIBLE
                                binding.stateFitting.visibility = View.VISIBLE
                                binding.stateFitting2.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(bluetoothChangeReceiver, filter)

        /* Permission - Camera */
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(cameraPermissionList)
        }

        /* FaceMesh */
        prefs = baseContext.getSharedPreferences("faceSetting", Context.MODE_PRIVATE)
        faceMeshSettings = booleanArrayOf(
            prefs.getBoolean("eye", false),
            prefs.getBoolean("eyeBrow", false),
            prefs.getBoolean("eyePupil", false),
            prefs.getBoolean("lib", false),
            prefs.getBoolean("faceMesh", false),
            prefs.getBoolean("faceLine", false)
        )
        faceMeshColors = arrayListOf(
            colorLoad(prefs.getInt("eyeColor", 5)),
            colorLoad(prefs.getInt("eyeBrowColor", 4)),
            colorLoad(prefs.getInt("eyePupilColor", 1)),
            colorLoad(prefs.getInt("libColor", 3)),
            colorLoad(prefs.getInt("faceMeshColor", 1)),
            colorLoad(prefs.getInt("faceLineColor", 1))
        )
        prefs = baseContext.getSharedPreferences("alarm", Context.MODE_PRIVATE)
        alarmState = prefs.getBoolean("alarmState", false)

        initFaceMesh()
        initGlSurfaceView()
        postGlSurfaceView()
        with(binding.preview) {
            removeAllViewsInLayout()
            addView(glSurfaceView)
            requestLayout()
        }
    }

    override fun onStart() {
        super.onStart()
        connectGatt()
    }

    override fun onResume() {
        super.onResume()

        isCameraOn = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        /* FaceMesh */
        if (isCameraOn) {
            binding.faceFitting.visibility = View.VISIBLE
            postGlSurfaceView()
        } else {
            binding.faceFitting.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)

        /* FaceMesh */
        if (isCameraOn) {
            timer2.cancel()
            glSurfaceView.visibility = View.GONE
            cameraInput.close()
        }
        timer2.cancel()
    }

    override fun onStop() {
        super.onStop()
        disconnectGatt()
    }
}