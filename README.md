# Maekom Code Tracker

**Maekom Code Tracker**는 Spring Boot + GitLab 저장소(단일 및 멀티 모듈 지원)의 타인 작업 이력과 아키텍처 변경 흐름을 사후 분석하기 위한 **IntelliJ IDEA용 100% 로컬 오프라인 읽기 전용(Read-Only) 코드 및 워크플로우 분석 플러그인**입니다.

PostgreSQL(JPA + MyBatis), Redis, MongoDB 및 외부 MSA/레거시 API 연동이 혼재된 백엔드 프로젝트에서, 브랜치·기간·작성자·키워드 기준으로 Git 커밋과 저장소 내 마크다운(`.md`) 문서를 시간순 작업 에피소드로 묶어 **수정 위치 스마트 포인터**와 **아키텍처 근거 기반 수정 사유(Why)**를 제공합니다.

---

## 주요 기능

- **2단계 온디맨드(On-Demand) Git 분석**
  - 1단계(목록 수집): 지정 기간 최대 500커밋까지 `git log --find-renames --name-status`만 경량 조회하여 타임라인을 즉시 구성합니다. (1MB 초과 대용량 덤프/바이너리 파일 Diff 파싱 자동 스킵)
  - 2단계(상세 분석): 커밋 선택 시 해당 단일 커밋의 `git show --find-renames --unified=3 <hash>`를 백그라운드에서 파싱하여 Hunk, 주석, 체킹 배지를 도출합니다.
- **작업 에피소드 그룹핑 & `.md` 맥락 자동 연결**
  - 동일 작성자의 4시간 이내 연속 커밋 또는 같은 날짜 커밋을 하나의 작업 에피소드로 묶습니다.
  - 동일 에피소드 또는 직전 에피소드에서 수정된 `.md` 파일의 헤딩(`#`, `##`, `###`) 및 체크리스트(`- [x]`)를 AST 기반으로 추출하여 코드 커밋의 수정 파일/메서드/도메인 키워드와 자동 매칭·인용합니다.
- **주(Primary) 레이어 + 연관 기술 스택(Sub-Tags) 이중 분류**
  - 1순위 주 레이어: `Controller/API`, `Service`, `External/MSA-Client`, `RDB/JPA-MyBatis`, `NoSQL/Mongo`, `Cache/Redis`, `Config/Security`, `Docs(.md)`, `Test`, `Etc`
  - 2순위 연관 스택 태그: `#JPA/MyBatis`, `#MongoDB`, `#Redis`, `#MSA/Legacy-API`
- **스마트 코드 위치 포인터 & 표준 Diff 비교**
  - Unified Diff Hunk(`@@ -old,len +new,len @@`)를 파싱해 변경 시작 라인(`📍 Line N`), 메서드/컨텍스트명, `+추가/-삭제` 라인 수, HTTP 매핑 및 외부 호출 URI를 추출합니다.
  - 포인터 클릭 시 워크스페이스 파일에서 추출된 메서드 위치를 우선 탐색하여 이동(미발견 시 `newStartLine`으로 이동)하며, 우클릭 또는 버튼으로 표준 `Before / After` Diff 뷰어를 실행합니다.
- **5중 로컬 근거 기반 '수정 사유(Why)' & 아키텍처 체킹 배지**
  - 커밋 본문(`%B`) 및 이슈 태그(`[JIRA-...]`, `#이슈번호`), 연관 `.md` 문단 인용, 새로 추가된 주석(`+`), 6대 현업 체킹 배지(`[트랜잭션 경계 변경]`, `[외부 API + DB 혼합 주의]`, `[Redis 캐시/키 변경]`, `[MongoDB 스키마/쿼리 변경]`, `[MyBatis/SQL 쿼리 변경]`, `[API 스펙 변경]`), 크로스 레이어 흐름 한 줄 요약을 제공합니다.
- **수동 온디맨드 업데이트 확인**
  - 우측 Tool Window 상단 헤더 바의 **[업데이트 확인]** 버튼을 클릭할 때만 GitHub Releases 최신 버전을 확인합니다.

---

## 로컬 오프라인 읽기 전용 보안 원칙

1. **원본 저장소 무변경(Read-Only)**: 대상 Git 저장소 및 프로젝트 파일에 대한 쓰기·수정·삭제(`git commit`, `checkout`, `reset` 등) 명령을 일절 수행하지 않으며, 허용된 읽기 전용 Git 조회 명령만 실행합니다.
2. **코드 외부 전송 없음**: 모든 코드·Git·문서 분석은 100% 로컬 오프라인에서 동작합니다. 텔레메트리나 백그라운드 자동 통신을 수행하지 않으며, 사용자가 **[업데이트 확인]** 버튼을 직접 클릭했을 때만 공개 GitHub Releases 메타데이터를 조회합니다.

---

## IntelliJ IDEA 설치 방법

1. [GitHub Releases](https://github.com/maekom-hub/maekom-code-tracker/releases)에서 최신 `maekom-code-tracker-<버전>.zip` 파일을 다운로드합니다.
2. IntelliJ IDEA에서 **Settings (또는 Preferences) > Plugins > ⚙️ (톱니바퀴) > Install Plugin from Disk...** 를 선택합니다.
3. 다운로드한 `.zip` 파일을 선택하여 설치한 뒤 IDE를 재시작합니다.
4. 우측 사이드바의 **Maekom Code Tracker** Tool Window를 열어 사용합니다.

---

## 버전 이력

<!-- VERSION_HISTORY_START -->
| 버전 | 릴리즈 날짜 | 주요 변경 요약 | 다운로드 |
| --- | --- | --- | --- |
| `v0.1.5` | 2026-11-24 | 스마트 위치 포인터 실제 변경 라인 오프셋(±3줄 오차) 보정 및 Diff 창 해당 라인 자동 스크롤 추가 | [maekom-code-tracker-0.1.5.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.5/maekom-code-tracker-0.1.5.zip) |
| `v0.1.4` | 2026-11-24 | 스마트 위치 포인터 하위 연관 .md 수정 근거 요약 및 툴팁 표시 추가 | [maekom-code-tracker-0.1.4.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.4/maekom-code-tracker-0.1.4.zip) |
| `v0.1.3` | 2026-11-24 | Custom Plugin Repository(updatePlugins.xml) 및 IntelliJ Plugins 창 원클릭 업데이트 연동 | [maekom-code-tracker-0.1.3.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.3/maekom-code-tracker-0.1.3.zip) |
| `v0.1.2` | 2026-11-24 | 상단 타이틀 바 통합, 필터 UI 아이콘 경량화, 상세 패널 폰트 축소 및 import/공백 포인터 제외 | [maekom-code-tracker-0.1.2.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.2/maekom-code-tracker-0.1.2.zip) |
| `v0.1.0` | 2026-11-24 | Maekom Code Tracker v0.1.0 초기 릴리즈 (Spring Boot/GitLab 읽기 전용 워크플로우 및 스마트 코드 포인터) | [maekom-code-tracker-0.1.0.zip](https://github.com/maekom-hub/maekom-code-tracker/releases/download/v0.1.0/maekom-code-tracker-0.1.0.zip) |
<!-- VERSION_HISTORY_END -->

---

## 소스 코드 로컬 빌드 방법

JDK 21 환경에서 아래 명령으로 플러그인 배포 아카이브(`build/distributions/maekom-code-tracker-*.zip`)를 빌드할 수 있습니다.

```bash
./gradlew buildPlugin
```
