# Prototype
 prototype for FaceMesh
# 이 프로젝트는 MediaPipe FaceMesh를 설명하기 위한 프로토타입 입니다. 프로젝트에 대한 설명은 여기에 있습니다.
1. 먼저 build.gradle(Module)과 manifest에 필요한 코드들을 입력해 줍니다.
```
    <!-- FaceMesh -->
    <uses-permission android:name="android.permission.CAMERA" />

    <uses-feature android:name="andr0oid.hardware.camera" />
    <uses-feature android:name="android.hardware.camera.autofocus" />
    <uses-feature
        android:glEsVersion="0x00020000"
        android:required="true" /> <!-- Warning -->
```
```
    // MediaPipe
    implementation 'com.google.mediapipe:facemesh:latest.release'
```