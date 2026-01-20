package com.fly.forgotyet.entity;

import lombok.Data;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@Entity
@Table(name = "t_app_config")
public class AppConfig {

    @Id
    @Column(length = 100)
    private String configKey;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String configValue;

    private String description;
}