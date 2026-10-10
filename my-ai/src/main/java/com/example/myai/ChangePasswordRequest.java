package com.example.myai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {
    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "长度不能超过 128")
    private String currentPassword;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 128, message = "长度需要在 6 到 128 之间")
    private String newPassword;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
