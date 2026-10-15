# ⚔️ GamelifeHealthBar

> **Lightweight & Standalone Overhead HealthBar Plugin for Minecraft 1.21.x**

![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.x-brightgreen?logo=minecraft)
![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur-blue)
![Java Version](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

**GamelifeHealthBar** là một plugin hiển thị thanh máu pixel động trên đầu Mobs và Players. Plugin hoạt động hoàn toàn độc lập, **KHÔNG phụ thuộc** vào `HUDEngine` hay `GamelifeHud`.

---

## ✨ Tính năng nổi bật

- 🩸 **Overhead Healthbar**: Hiển thị thanh máu dạng Pixel chân thực khi nhìn vào mục tiêu hoặc khi gây sát thương.
- 🎨 **Tự động tạo Resource Pack**: Tự động xuất file resource pack chứa font và 10 khung máu độc quyền (`plugins/GamelifeHealthBar/gamelifehealthbar.zip`).
- 🚚 **Gửi Pack đa dạng**:
    - Hỗ trợ các chế độ delivery: `none`, `url`, và tích hợp sẵn `host` HTTP server.
    - Tùy chỉnh chế độ bắt buộc (required), thông báo join, kick message và thứ tự pack.
- 🔄 **Reload nhanh chóng**: Lệnh `/ghb reload` cập nhật cấu hình tức thì không cần restart server.

---

## 🛠️ Hướng dẫn Build

### Yêu cầu hệ thống:
* **JDK 21**
* **Maven 3.9+** (hoặc Maven Wrapper)

### Các bước thực hiện:
Mở terminal tại thư mục dự án và chạy lệnh:

```bash
mvn package