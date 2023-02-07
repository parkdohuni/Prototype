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
        // 얼굴 인식
        faceMesh = FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(true)
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
    //얼굴 그물망
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

                                }
                                cancel()
                            }
                        }
                    }
                } else {
                    reset()
                    binding.faceFitting.visibility = View.VISIBLE
                    binding.state.visibility = View.GONE

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
        return now
    }

    //시간 차 구하기
    private fun betweenTime(before: Long): Long {
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

            // EAR은 EAR(Eye Aspect Ratio) 알고리즘을 이용해 나타낸 비율이며 사용자가 현재 눈을 감고 있는지 파악하기 위해 사용되고
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

            //MAR은 입이 닫혀진 비율로 눈과 같은 방법을 통해 입으로 계산한 비율
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
                count++ //눈을 감았으니 카운트
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

                    count = 0
                    blink = 1
                    totalBlink++
                    beforeCheck = true
                    longClosedState = false
                }
                // leftEye, rightEye : 왼쪽과 오른쪽 눈을 구분하여 각각의 동공이 어느 방향을 바라보는지 나타냄.
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
    //거리 구하는 함수
    private fun distance(rx: Float, lx: Float, ry: Float, ly: Float): Float {
        return sqrt((rx - lx).pow(2) + (ry - ly).pow(2))
    }
    // 왼쪽과 오른쪽 눈을 구분하여 각각의 동공이 어느 방향을 바라보는지 나타냄
    private fun eyeDirection(ld: Float, rd: Float): String {
        return if ((ld - rd) > 0.004)
            getString(R.string.left)
        else if ((ld - rd) < -0.0035)
            getString(R.string.right)
        else
            getString(R.string.front)

    }
    // 현재 얼굴이 어느 방향인지 정면, 아래, 왼쪽, 오른쪽 4가지 방향으로 구분하여 나타내며
    // 정면과 아래 방향인 경우는 얼굴의 이마 점과 턱 점을 중심으로 렌즈와의 거리인 z방향 좌표를 이용해 두 거리의 차를 이용하여 방향을 구함.
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startWarningOn() {
        toneGenerator1.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)

        lifecycleScope.launch(Dispatchers.Main) {

            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startWarningOff() {

        lifecycleScope.launch(Dispatchers.Main) {

            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun continueWarning() {
        toneGenerator2.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)

        lifecycleScope.launch(Dispatchers.Main) {

            with(binding.warningFilter) {
                visibility = View.VISIBLE
                (drawable as AnimationDrawable).start()
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

        // 화면 항상 켜짐
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toNewTimerTask()

        /* FaceMesh */
        prefs = baseContext.getSharedPreferences("faceSetting", Context.MODE_PRIVATE)
        faceMeshSettings = booleanArrayOf(
            prefs.getBoolean("eye", false),
            prefs.getBoolean("eyeBrow", false),
            prefs.getBoolean("eyePupil", false),
            prefs.getBoolean("lib", false),
            prefs.getBoolean("faceMesh", false),
            prefs.getBoolean("faceLine", false)
        )//얼굴에 그물망을 그리는 변수, 여기서 하나라도 true로 바꾸면 오류가 발생한다.
        faceMeshColors = arrayListOf(
            colorLoad(prefs.getInt("eyeColor", 5)),
            colorLoad(prefs.getInt("eyeBrowColor", 4)),
            colorLoad(prefs.getInt("eyePupilColor", 1)),
            colorLoad(prefs.getInt("libColor", 3)),
            colorLoad(prefs.getInt("faceMeshColor", 1)),
            colorLoad(prefs.getInt("faceLineColor", 1))
        )//그물망에 대한 색 변경
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
    }
}