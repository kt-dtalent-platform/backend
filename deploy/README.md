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

로컬 `backend` 폴더에서 jar를 만들어 서버로 복사합니다.

```bash
./gradlew bootJar
scp -i <키 파일> build/libs/backend-0.0.1-SNAPSHOT.jar ubuntu@<서버주소>:~/kt-backend/app.jar
```

서버에서 처음이면 실행하고, 이미 떠 있으면 앱만 재시작합니다.

```bash
cd ~/kt-backend
docker compose up -d        # 처음 실행
docker compose restart app  # jar만 바꿨을 때
```

## 확인하기

- 상태: `http://<서버주소>:8080/actuator/health` 에서 `"status":"UP"`
- Swagger: `http://<서버주소>:8080/swagger-ui/index.html`
- 로그: 서버에서 `docker compose logs -f app`

## 테스트 데이터

`scripts/local/seed.sql`의 계정 비밀번호는 공개 저장소에 적혀 있습니다.
개발 서버는 인터넷에 열려 있으므로, 시드를 넣었다면 계정 비밀번호를 바로 바꾸고 새 비밀번호는 팀 DM으로만 공유하세요.
