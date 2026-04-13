IF DB_ID(N'myai') IS NULL
BEGIN
  CREATE DATABASE [myai];
END;
GO

IF SUSER_ID(N'myai_user') IS NULL
BEGIN
  CREATE LOGIN [myai_user] WITH PASSWORD = N'change-me', CHECK_POLICY = OFF, CHECK_EXPIRATION = OFF;
END;
GO

USE [myai];
GO

IF USER_ID(N'myai_user') IS NULL
BEGIN
  CREATE USER [myai_user] FOR LOGIN [myai_user];
END;
GO

ALTER ROLE [db_owner] ADD MEMBER [myai_user];
GO
