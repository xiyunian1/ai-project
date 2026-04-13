@echo off
cd /d "%~dp0"
echo Starting MyAI Backend with H2 Database...
start "MyAI Backend" cmd /k ".\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--spring.datasource.url=jdbc:h2:mem^:testdb;MODE=MSSQLServer;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect --spring.jpa.hibernate.ddl-auto=create-drop"
