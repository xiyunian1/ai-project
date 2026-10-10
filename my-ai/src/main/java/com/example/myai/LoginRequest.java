package com.example.myai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {
    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "长度不能超过 64")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "长度不能超过 128")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
