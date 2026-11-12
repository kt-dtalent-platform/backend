# 개발 서버 배포

프론트 연동용 개발 서버에 백엔드와 MySQL을 띄우는 방법입니다. 운영 서버용이 아닙니다.

| 항목 | 내용 |
| --- | --- |
| 구성 | MySQL 8.0 컨테이너 + Java 21 컨테이너에서 `app.jar` 실행 |
| 외부 공개 포트 | 8080 (MySQL 3306은 공개하지 않음) |
| 서버 폴더 | `~/kt-backend` |
| 비밀값 | 서버의 `~/kt-backend/.env`에만 둠. Git·노션·단톡에 올리지 않음 |

## 처음 한 번: 서버 준비

서버에 폴더와 설정 파일을 만듭니다. `<서버주소>`와 `<키 파일>`은 팀 내부 공유 값을 쓰세요.

```bash
ssh -i <키 파일> ubuntu@<서버주소> 'mkdir -p ~/kt-backend'
scp -i <키 파일> deploy/compose.yaml deploy/.env.example ubuntu@<서버주소>:~/kt-backend/
```

서버에 접속해 `.env`를 만들고, 비밀값은 서버에서 새로 생성합니다.

```bash
cd ~/kt-backend
cp .env.example .env
chmod 600 .env
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" .env
sed -i "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)|" .env
sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 32)|" .env
```

## 배포하기

### 자동 배포 (기본)

main에 머지되면 `Backend CI`가 끝난 뒤 `Deploy to dev server` 워크플로(`.github/workflows/deploy.yml`)가 자동으로 배포합니다.

1. CI가 테스트한 커밋으로 jar를 빌드
2. 보안 그룹에 GitHub 실행 서버 IP를 SSH(22)로 잠깐 허용
3. jar와 `deploy/deploy.sh`를 서버로 복사하고 스크립트 실행
   - DB·jar 백업(`~/kt-backend/backup`, 최근 5개 유지) → 앱 중지 → jar 교체 → 시작 → health 확인
   - health가 실패하면 이전 jar로 되돌리고 워크플로는 실패로 끝남
4. 임시 SSH 규칙 삭제 (배포가 실패해도 항상 실행)

- CI가 실패하면 배포하지 않습니다.
- 재배포가 필요하면 GitHub **Actions → Deploy to dev server → Run workflow**(main)로 다시 실행합니다.
- 재시작하는 20~40초 동안 Swagger가 끊깁니다. migration이 들어간 PR은 머지 전에 팀에 알려 주세요.

필요한 설정 (저장소 Settings → Secrets and variables → Actions)

| 종류 | 이름 | 내용 |
| --- | --- | --- |
| Secret | `EC2_SSH_KEY` | 배포 전용 SSH 개인키 (서버 `authorized_keys`의 `github-actions-deploy` 줄과 짝) |
| Variable | `AWS_DEPLOY_ROLE_ARN` | GitHub OIDC로 쓰는 IAM 역할. 이 보안 그룹의 규칙 추가·삭제만 허용, main 브랜치에서만 사용 가능 |
| Variable | `EC2_HOST` | 서버 주소 |
| Variable | `EC2_SECURITY_GROUP_ID` | 서버 보안 그룹 ID |
| Variable | `EC2_KNOWN_HOSTS` | 서버 SSH 호스트 공개키 한 줄 (`ssh-keyscan -t ed25519 <서버주소>`) |

자동 배포를 그만두려면 서버 `~/.ssh/authorized_keys`에서 `github-actions-deploy` 줄을 지우고 `EC2_SSH_KEY`를 삭제합니다.

### 수동 배포 (예비)

자동 배포를 쓸 수 없을 때만 씁니다. **main 코드로** jar를 만들어야 하므로, 작업 중인 브랜치가 아니라 main을 별도 폴더로 꺼내서 빌드합니다.

```bash
git fetch origin && git worktree add ../kt-main-deploy origin/main
cd ../kt-main-deploy && ./gradlew bootJar -x test
scp -i <키 파일> build/libs/backend-0.0.1-SNAPSHOT.jar ubuntu@<서버주소>:~/kt-backend/app.jar.new
scp -i <키 파일> deploy/deploy.sh ubuntu@<서버주소>:~/kt-backend/deploy.sh
ssh -i <키 파일> ubuntu@<서버주소> 'bash ~/kt-backend/deploy.sh'
cd - && git worktree remove ../kt-main-deploy
```

처음 서버를 띄울 때는 서버에서 `sudo docker compose up -d`로 실행합니다. 서버의 `ubuntu` 계정은 docker 그룹이 아니어서 `sudo`가 필요합니다.

## 확인하기

- 상태: `http://<서버주소>:8080/actuator/health` 에서 `"status":"UP"`
- Swagger: `http://<서버주소>:8080/swagger-ui/index.html`
- 로그: 서버에서 `sudo docker compose logs -f app`
- 배포 기록: GitHub Actions 탭의 `Deploy to dev server`

## 테스트 데이터

`scripts/local/seed.sql`의 계정 비밀번호는 공개 저장소에 적혀 있습니다.
개발 서버는 인터넷에 열려 있으므로, 시드를 넣었다면 계정 비밀번호를 바로 바꾸고 새 비밀번호는 팀 DM으로만 공유하세요.
