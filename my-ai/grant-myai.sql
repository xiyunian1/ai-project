USE [myai];
GO
IF NOT EXISTS (
  SELECT 1
  FROM sys.database_role_members rm
  JOIN sys.database_principals r ON rm.role_principal_id = r.principal_id
  JOIN sys.database_principals m ON rm.member_principal_id = m.principal_id
  WHERE r.name = N'db_owner' AND m.name = N'myai_user'
)
BEGIN
  EXEC sp_addrolemember N'db_owner', N'myai_user';
END;
GO
