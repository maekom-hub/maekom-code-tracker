# Maekom Code Tracker

**Maekom Code Tracker**는 Spring Boot + GitLab 저장소(단일 및 멀티 모듈 지원)의 타인 작업 이력과 아키텍처 변경 흐름을 사후 분석하기 위한 **IntelliJ IDEA용 100% 로컬 오프라인 읽기 전용(Read-Only) 코드 및 워크플로우 분석 플러그인**입니다.

PostgreSQL(JPA + MyBatis), Redis, MongoDB 및 외부 MSA/레거시 API 연동이 혼재된 실무 백엔드 프로젝트에서, 브랜치·기간·작성자·키워드 기준으로 Git 커밋과 저장소 내 마크다운(`.md`) 문서를 시간순 작업 에피소드로 묶어 **수정 위치 스마트 포인터**와 **아키텍처 근거 기반 수정 사유(Why)**를 제공합니다.

---

## 주요 기능

- **2단계 온디맨드(On-Demand) 분석 엔진**
  - 1단계(목록 수집): 지정 기간 최대 500커밋까지 `git log --find-renames --name-status`만 경량 조회하여 타임라인을 즉시 구성합니다. (1MB 초과 대용량 덤프/바이너리 파일 Diff 파싱 자동 스킵)
  - 2단계(상세 분석): 커밋 선택 시 해당 단일 커밋의 `git show --find-renames --unified=3 <hash>`를 백그라운드에서 파싱하여 Hunk, 주석, 체킹 배지를 도출합니다.
- **작업 에피소드 그룹핑 & `.md` 맥락 자동 연결**
  - 동일 작성자의 4시간 이내 연속 커밋 또는 같은 날짜 커밋을 하나의 작업 에피소드로 묶습니다.
  - 동일 에피소드 또는 직전 에피소드에서 수정된 `.md` 파일의 헤딩(`#`, `##`, `###`) 및 체크리스트(`- [x]`)를 AST(`commonmark`) 기반으로 추출하여 코드 커밋의 수정 파일/메서드/도메인 키워드와 자동 매칭·인용합니다.
- **주(Primary) 레이어 + 연관 기술 스택(Sub-Tags) 이중 분류**
  - 1순위 주 레이어: `Controller/API`, `Service`, `External/MSA-Client`, `RDB/JPA-MyBatis`, `NoSQL/Mongo`, `Cache/Redis`, `Config/Security`, `Docs(.md)`, `Test`, `Etc`
  - 2순위 연관 스택 태그: `#JPA/MyBatis`, `#MongoDB`, `#Redis`, `#MSA/Legacy-API`
- **스마트 코드 위치 포인터 & 표준 Diff 비교**
  - Unified Diff Hunk(`@@ -old,len +new,len @@`)를 파싱해 변경 시작 라인(`📍 Line N`), 메서드/컨텍스트명, `+추가/-삭제` 라인 수, HTTP 매핑 및 외부 호출 URI를 추출합니다.
  - 포인터 클릭 시 워크스페이스 파일에서 추출된 메서드 위치를 우선 탐색하여 이동(미발견 시 `newStartLine`으로 이동)하며, 우클릭 또는 버튼으로 표준 `Before / After` Diff 뷰어를 실행합니다.
- **5중 로컬 근거 기반 '수정 사유(Why)' & 아키텍처 체킹 배지**
  - 커밋 본문(`%B`) 및 이슈 태그(`[JIRA-...]`, `#이슈번호`), 연관 `.md` 문단 인용, 새로 추가된 주석(`+`), 6대 현업 체킹 배지(`[트랜잭션 경계 변경]`, `[외부 API + DB 혼합 주의]`, `[Redis 캐시/키 변경]`, `[MongoDB 스키마/쿼리 변경]`, `[MyBatis/SQL 쿼리 변경]`, `[API 스펙 변경]`), 크로스 레이어 흐름 한 줄 요약을 제공합니다.
- **수동 온디맨드 업데이트 확인 (상단 헤더 바)**
  - 우측 Tool Window 전용 상단 헤더 바에 현재 설치된 버전(`v0.1.1` 동적 표시)과 **[업데이트 확인]** 버튼을 제공합니다.
  - 사용자가 직접 버튼을 클릭할 때만 JDK 표준 `java.net.http.HttpClient`로 GitHub Releases 최신 태그를 1회 조회하며, 최신 버전 안내 및 릴리즈 페이지 이동을 지원합니다.

---

## 로컬 오프라인 읽기 전용 보안 원칙

1. **기본 오프라인 & 무텔레메트리 원칙**: 모든 코드·Git·문서 분석은 100% 로컬 오프라인에서 동작합니다. 플러그인 시작 시 백그라운드 자동 호출이나 텔레메트리·소스코드 외부 전송을 일절 수행하지 않으며, 오직 사용자가 상단 헤더의 **[업데이트 확인]** 버튼을 직접 클릭했을 때만 공개 GitHub Releases 메타데이터를 읽기 전용으로 조회합니다.
2. **절대 읽기 전용(Read-Only)**: 대상 Git 저장소 및 프로젝트 파일에 대한 쓰기·수정·삭제(`git commit`, `checkout`, `reset` 등) 명령을 일절 수행하지 않으며, 허용된 읽기 전용 Git 조회 명령만 실행합니다.
3. **최소 의존성 원칙**: IntelliJ Platform SDK, 번들 `Git4Idea`, Kotlin/JDK 표준 라이브러리(`java.net.http.HttpClient` 포함) 및 경량 BSD-2-Clause 마크다운 파서(`org.commonmark:commonmark`)만 사용합니다.

---

## 빌드, 관리 (`manage.sh`) 및 GitHub Actions 자동 배포

- **GitHub Actions 태그 자동 배포 (`.github/workflows/release.yml`)**:
  - `./manage.sh release <버전>` 실행으로 `v*` 태그가 GitHub에 푸시되면 GitHub Actions(`ubuntu-latest`, JDK 21 Temurin)가 자동으로 `./gradlew buildPlugin`을 실행합니다.
  - 빌드가 완료되면 `softprops/action-gh-release@v2`를 통해 GitHub Release를 자동 생성하고 `build/distributions/maekom-code-tracker-<버전>.zip` 배포 아카이브를 첨부합니다. (`workflow_dispatch` 수동 빌드 검증도 지원)

```bash
# 저장소 및 origin 원격 저장소 초기화/검증
./manage.sh init --fix-remote

# 플러그인 빌드 (build/distributions/maekom-code-tracker-<버전>.zip 생성)
./manage.sh build

# 현재 버전, 원격 저장소, 브랜치, 태그, 최근 커밋 확인
./manage.sh status

# 릴리즈 배포 (버전 갱신, README 이력 추가, 커밋/푸시/v* 태그 푸시 -> GitHub Actions 자동 릴리즈)
./manage.sh release 0.1.1 "GitHub Actions 자동 배포 및 수동 업데이트 확인 기능 추가"

# 안전 백업 브랜치 생성 후 롤백 및 재빌드
./manage.sh rollback

# 빌드 산출물 정리
./manage.sh clean
```

---

## 버전 이력

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 주요 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
| `v0.1.1` | 2026-11-24 | GitHub Actions 자동 배포 워크플로우 및 수동 업데이트 확인 기능 추가 | [maekom-code-tracker-0.1.1.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.1/maekom-code-tracker-0.1.1.zip) |
| `v0.1.0` | 2026-11-24 | Maekom Code Tracker v0.1.0 초기 릴리즈 | [maekom-code-tracker-0.1.0.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.0/maekom-code-tracker-0.1.0.zip) |
| `v0.1.0` | 2026-11-24 | Maekom Code Tracker v0.1.0 초기 릴리즈 (Spring Boot/GitLab 읽기 전용 워크플로우 및 스마트 코드 포인터) | [maekom-code-tracker-0.1.0.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.0/maekom-code-tracker-0.1.0.zip) |
<!-- VERSION_HISTORY_END -->
