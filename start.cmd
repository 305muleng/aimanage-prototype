@echo off
chcp 65001 >nul
echo 正在启动“爱管理”原型...
echo 启动完成后请访问 http://localhost:8080
if exist target\aimanage-prototype-0.0.1-SNAPSHOT.jar goto run
mvn "-Dmaven.repo.local=%~dp0.m2-cache" -DskipTests package
if errorlevel 1 (
  echo 项目打包失败，请检查上面的错误信息。
  pause
  exit /b 1
)
:run
java -jar target\aimanage-prototype-0.0.1-SNAPSHOT.jar
pause
