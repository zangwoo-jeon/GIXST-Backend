# 빌드 스테이지
FROM gradle:8-jdk17-jammy AS build

# Gradle 캐시를 위한 볼륨 설정
VOLUME /home/gradle/.gradle
WORKDIR /app
COPY . .
RUN gradle build -x test --no-daemon

# 실행 스테이지
FROM eclipse-temurin:17-jre
WORKDIR /app

# 보안을 위한 non-root 사용자 생성
RUN groupadd -r spring && useradd -r -g spring spring

# Logback이 사용할 로그 디렉토리 생성 및 권한 설정
# WORKDIR 이후, USER spring:spring 이전에 추가
RUN mkdir /logs && chown spring:spring /logs
RUN mkdir /newsLogs && chown spring:spring /newsLogs

USER spring:spring

# 빌드 스테이지에서 생성된 JAR 파일 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 환경 변수 설정
ENV TZ=Asia/Seoul
ENV JAVA_OPTS="-Xms512m -Xmx1024m -Duser.timezone=Asia/Seoul"
ENV SPRING_PROFILES_ACTIVE=prod

# 포트 노출
EXPOSE 8080

# 애플리케이션 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
