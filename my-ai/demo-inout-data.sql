USE [myai];
GO

IF NOT EXISTS (SELECT 1 FROM dbo.inout WHERE [商品编号] = N'WMS-DEMO-001')
BEGIN
  INSERT INTO dbo.inout (
    [按日期], [单据类型], [供应商名称], [商品编号], [商品名称],
    [商品规格], [库存数量], [现有库存], [单据编号], [有效期至]
  )
  VALUES (
    N'2026-05-09', N'Demo Stock Check', N'East Food Supplier', N'WMS-DEMO-001', N'Demo Low Stock Apple',
    N'Box/24kg', 3, 3, N'DEMO-STOCK-001', N'2026-09-30'
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.inout WHERE [商品编号] = N'WMS-DEMO-002')
BEGIN
  INSERT INTO dbo.inout (
    [按日期], [单据类型], [供应商名称], [商品编号], [商品名称],
    [商品规格], [库存数量], [现有库存], [单据编号], [有效期至]
  )
  VALUES (
    N'2026-05-09', N'Demo Stock Check', N'South Packing Supplier', N'WMS-DEMO-002', N'Demo Low Stock Carton',
    N'Unit/Standard', 7, 7, N'DEMO-STOCK-002', N'2027-01-31'
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.inout WHERE [商品编号] = N'WMS-DEMO-003')
BEGIN
  INSERT INTO dbo.inout (
    [按日期], [单据类型], [供应商名称], [商品编号], [商品名称],
    [商品规格], [库存数量], [现有库存], [单据编号], [有效期至]
  )
  VALUES (
    N'2026-05-09', N'Demo Stock Check', N'North Grain Supplier', N'WMS-DEMO-003', N'Demo Safe Stock Rice',
    N'Bag/10kg', 120, 120, N'DEMO-STOCK-003', N'2026-12-31'
  );
END;
GO

UPDATE dbo.inout
SET [单据类型] = N'Demo Stock Check',
    [供应商名称] = N'East Food Supplier',
    [商品名称] = N'Demo Low Stock Apple',
    [商品规格] = N'Box/24kg'
WHERE [商品编号] = N'WMS-DEMO-001';
GO

UPDATE dbo.inout
SET [单据类型] = N'Demo Stock Check',
    [供应商名称] = N'South Packing Supplier',
    [商品名称] = N'Demo Low Stock Carton',
    [商品规格] = N'Unit/Standard'
WHERE [商品编号] = N'WMS-DEMO-002';
GO

UPDATE dbo.inout
SET [单据类型] = N'Demo Stock Check',
    [供应商名称] = N'North Grain Supplier',
    [商品名称] = N'Demo Safe Stock Rice',
    [商品规格] = N'Bag/10kg'
WHERE [商品编号] = N'WMS-DEMO-003';
GO

SELECT [商品编号], [商品名称], [供应商名称], [现有库存], [有效期至]
FROM dbo.inout
WHERE [商品编号] LIKE N'WMS-DEMO-%'
ORDER BY [现有库存] ASC;
GO
