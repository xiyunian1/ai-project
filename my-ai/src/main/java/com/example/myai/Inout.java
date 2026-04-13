package com.example.myai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Entity
@Table(name = "inout")
@Data
public class Inout {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "按日期")
    private String date;

    @Column(name = "单据类型")
    private String docType;

    @Column(name = "供应商名称")
    private String supplierName;

    @Column(name = "商品编号")
    private String productId;

    @Column(name = "商品名称")
    private String productName;

    @Column(name = "商品规格")
    private String productSpec;

    @Column(name = "库存数量")
    private BigDecimal stockQty;

    @Column(name = "现有库存")
    private BigDecimal currentStock;

    @Column(name = "单据编号")
    private String docId;

    @Column(name = "有效期至")
    private String expiryDate;
}
