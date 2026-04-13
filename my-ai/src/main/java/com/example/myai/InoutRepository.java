package com.example.myai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface InoutRepository extends JpaRepository<Inout, Long> {

    @Query("SELECT i FROM Inout i WHERE i.productName LIKE %:name%")
    List<Inout> findByProductName(@Param("name") String name);

    @Query("SELECT i FROM Inout i WHERE i.currentStock < 10")
    List<Inout> findLowStock();

    @Query("SELECT i FROM Inout i WHERE i.currentStock < :threshold ORDER BY i.currentStock ASC")
    List<Inout> findLowStockByThreshold(@Param("threshold") BigDecimal threshold, Pageable pageable);

    @Query("SELECT i FROM Inout i ORDER BY i.currentStock DESC")
    List<Inout> findTopByCurrentStock(Pageable pageable);

    @Query("SELECT i FROM Inout i WHERE i.productName LIKE %:name% ORDER BY i.currentStock DESC")
    List<Inout> findByProductNameLikeOrderByCurrentStockDesc(@Param("name") String name, Pageable pageable);

    @Query("SELECT COALESCE(SUM(i.currentStock), 0) FROM Inout i")
    BigDecimal sumCurrentStock();

    @Query("SELECT COUNT(DISTINCT i.productName) FROM Inout i")
    Long countDistinctProducts();

    @Query(value = "SELECT COUNT(*) FROM dbo.inout", nativeQuery = true)
    Long countAllRowsNative();

    @Query(value = "SELECT DB_NAME()", nativeQuery = true)
    String currentDatabaseName();

    @Query(value = "SELECT @@SERVERNAME", nativeQuery = true)
    String currentServerName();
}
