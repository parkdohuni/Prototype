package com.saloris.prototype.util
const val HEART_RATE_THRESHOLD = 67
const val MAX_WARNING_LEVEL = 10
const val MAX_FITTING_LEVEL = 10

/* FaceMesh */
// Color
val RED_COLOR = floatArrayOf(1f, 0.2f, 0.2f, 1f)
val BLACK_COLOR = floatArrayOf(0.9f, 0.9f, 0.9f, 1f)
val BLUE_COLOR = floatArrayOf(0.0f, 0.5f, 0.9f, 0.5f)
val ORANGE_COLOR = floatArrayOf(1f, 0.9f, 0.5f, 0.2f)
val WHITE_COLOR = floatArrayOf(0.75f, 0.75f, 0.75f, 0.5f)
val GREEN_COLOR = floatArrayOf(0.2f, 1f, 0.2f, 1f)

// 기본 마스킹
const val TESSELATION_THICKNESS = 5
// 오른 눈
const val RIGHT_EYE_THICKNESS = 8
const val RIGHT_EYEBROW_THICKNESS = 8

// 왼 눈
const val LEFT_EYE_THICKNESS = 8
const val LEFT_EYEBROW_THICKNESS = 8

// 테두리 입술, 얼굴 white
const val FACE_OVAL_THICKNESS = 8
const val LIPS_THICKNESS = 8
const val VERTEX_SHADER = "uniform mat4 uProjectionMatrix;" +
        "attribute vec4 vPosition;" +
        "void main() {" +
        "   gl_Position = uProjectionMatrix * vPosition;" +
        "}"
const val FRAGMENT_SHADER = "precision mediump float;" +
        "uniform vec4 uColor;" +
        "void main() {" +
        "   gl_FragColor = uColor;" +
        "}"

val MOUTH_INDEX = listOf(78, 82, 13, 312, 30, 317, 14, 87, 61, 291) // 입 8 / 입술 2 (l, r)
val LEFT_EYE_INDEX = listOf(33, 158, 159, 133, 145, 153, 468) // 눈 6 (0: l, 2: u, 4: d) / 홍채 1
val RIGHT_EYE_INDEX = listOf(362, 385, 386, 263, 374, 380, 473) // 눈 6 (2: u, 3: r, 4: d) / 홍채 1
val BASE_INDEX = listOf(151, 6, 199) // 이마, 눈 중심, 턱
val FACE_PITCHING = listOf(10, 152, 237, 356)// 얼굴 상(이마), 하(턱), 좌(왼쪽 귀), 우(오른쪽 귀)
