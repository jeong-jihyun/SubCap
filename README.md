# SubCap (자막기록)

EBS 같은 강연 영상을 재생하면, 화면에 나오는 자막을 폰이 자동으로 읽어 기록해 주는 안드로이드 앱입니다.
개발 언어는 **Java**, 빌드 도구는 **Gradle**(Android Gradle Plugin 8.5.2, Gradle 8.7)입니다.

## 프로젝트 구조

```
SubCap/
├── settings.gradle                 프로젝트 이름, 모듈(:app) 선언
├── build.gradle                    플러그인 버전 선언 (최상위)
├── gradle.properties               Gradle 옵션 (AndroidX 사용 등)
├── gradle/wrapper/
│   └── gradle-wrapper.properties   사용할 Gradle 버전(8.7) 지정
├── .github/workflows/build.yml     GitHub에서 APK를 자동으로 빌드하는 설정(선택)
└── app/
    ├── build.gradle                앱 설정, 의존 라이브러리(ML Kit 한글 인식)
    └── src/main/
        ├── AndroidManifest.xml     권한, 화면(Activity), 서비스(Service) 등록
        ├── java/com/subcap/
        │   ├── MainActivity.java   화면과 버튼 동작
        │   ├── CaptureService.java 백그라운드 화면 캡처·자막 읽기·완료 알림
        │   └── Store.java          읽은 자막을 파일에 저장/읽기/삭제
        └── res/
            ├── layout/activity_main.xml   화면 배치
            └── values/strings.xml         화면에 쓰는 글자
```

## 주요 기능

1. **자막 위치 지정**: 화면 위에서 몇 %~몇 % 구간만 읽습니다. (기본 35~55%)
   앱 화면의 "마지막으로 읽은 글자"를 보고 맞춰 주세요.
2. **백그라운드 화면 읽기** (`CaptureService`)
   시작을 누르고 화면 녹화를 허용하면, 앱이 꺼져 있어도 일정 간격(기본 1초)으로 화면을 보고
   자막 구역을 잘라 글자를 읽습니다. 글자 인식은 폰 안에서(ML Kit) 처리되어 인터넷이 필요 없습니다.
3. **중복 정리**: 같은 자막이 여러 번 읽히면 하나로 합치고, 글자 인식 오차가 있으면 더 온전히 읽힌 쪽을 씁니다.
4. **시간표 기록**: `[분:초] 자막` 형식으로 파일에 쌓습니다. (`Store`)
5. **자동 종료 + 완료 알림**: 새 자막이 30초 동안 없으면 영상이 끝난 것으로 보고
   기록을 마무리한 뒤 폰 상단에 "자막 기록 완료" 알림을 띄웁니다. 알림을 누르면 앱이 열립니다.
6. **Claude로 보내기**: 기록 전체와 "쉽게 요약해 달라"는 요청 문구를 공유 기능으로 넘깁니다.
   공유 목록에서 Claude 앱을 고르면 됩니다.
7. **기록 지우기 / 중지**: 언제든 중지하거나 기록을 지울 수 있습니다.

## 빌드 방법

- **Android Studio**: `SubCap` 폴더를 `Open`으로 열면 Gradle이 자동 동기화됩니다.
  `Build > Build APK(s)` 또는 `Run`.
- **명령줄**: Gradle 8.7이 설치되어 있으면 프로젝트 폴더에서 `gradle assembleDebug`.
  결과: `app/build/outputs/apk/debug/app-debug.apk`
  (Gradle 래퍼 실행 파일을 쓰려면 한 번 `gradle wrapper`를 실행하세요.)
- **GitHub**: 이 폴더를 저장소에 올리면 Actions의 "Build APK"가 APK를 만들어 줍니다.

## 사용 순서

1. 앱을 열고 알림 허용 → 자막 위치와 간격 확인
2. **시작** → 화면 녹화 허용
3. EBS 영상을 **1배속**으로 재생 (배속을 올리면 자막이 빠집니다)
4. 영상이 끝나고 30초 뒤 "자막 기록 완료" 알림
5. 알림을 눌러 앱을 열고 **Claude로 보내기**

## 알아둘 점

- 영상은 화면에 재생되고 있어야 합니다. (안드로이드는 다른 앱 화면을 몰래 볼 수 없습니다.)
- 접이식 폰은 접고 펼 때 화면 크기가 달라져서, 기록 중에는 접고 펴지 마세요.
- 자막 방식이라 방송 대본과 똑같지는 않습니다. 이어 붙이기와 오탈자 정리는 Claude가 합니다.
- 개인 학습용으로 사용하세요.

## 점검 기록 (2026-10)
| 항목 | 결과 |
| --- | --- |
| README의 기본값 ↔ 코드 (자막 구역 35~55%, 간격 1초, 30초 무입력 시 자동 종료·알림, 공유 인텐트로 Claude 전송) | 일치 |
| 권한 선언 (포그라운드 서비스 MediaProjection, 알림) ↔ 사용 방식 | 일치 |
| 빌드·실행 | **미확인** (이 환경에서는 Android SDK와 Maven 저장소에 접근할 수 없음. 빌드는 위 GitHub Actions로 확인) |
| 참고 | `gradlew`와 `gradle-wrapper.jar`는 저장소에 없습니다. 그래서 Actions도 `gradle` 명령(8.7)을 직접 씁니다. |
