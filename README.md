# 微信消息自动隐藏助手

[![Android CI](https://github.com/zm123723/WeChatAutoHide/actions/workflows/android.yml/badge.svg)](https://github.com/zm123723/WeChatAutoHide/actions)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

自动监控微信消息列表，识别指定联系人的消息后自动隐藏聊天。

## ⚠️ 免责声明

**本项目仅供学习研究使用，请勿用于非法用途。使用本软件可能违反微信服务条款，使用风险自负。**

## ✨ 特性

- ✅ 监控微信消息列表变化
- ✅ 自动识别目标联系人消息
- ✅ 自动执行"不显示该聊天"操作
- ✅ 支持分组管理联系人
- ✅ 本地数据存储，保护隐私
- ✅ Material Design 3 界面

## 📋 前提条件

- **必须提前对目标联系人设置"消息免打扰"**
- Android 7.0 (API 24) 或更高版本
- 授予无障碍服务权限

## 📥 下载安装

### 方式一：从 Actions 下载（推荐）

1. 访问 [Actions](https://github.com/zm123723/WeChatAutoHide/actions) 页面
2. 点击最新的成功构建（绿色✅）
3. 滚动到底部 **Artifacts** 区域
4. 下载 `app-debug`
5. 解压后安装 APK

### 方式二：从 Releases 下载

从 [Releases](https://github.com/zm123723/WeChatAutoHide/releases) 页面下载最新版本。

## 🚀 使用说明

### 1. 授权步骤
1. 安装并打开应用
2. 点击"无障碍服务"卡片
3. 在设置中找到"微信助手"
4. 开启无障碍服务
5. 返回应用确认显示"✅ 已启用"

### 2. 添加联系人
1. 点击右下角 ➕ 按钮
2. 输入联系人名称（**必须与微信显示完全一致**）
3. 选择分组（工作/生活/其他）
4. 确认勾选"收到消息自动隐藏"
5. 点击"添加"

### 3. 使用流程
先在微信中对目标联系人开启"消息免打扰"
在本应用中添加该联系人到隐藏名单
当该联系人发送消息时
应用自动检测并隐藏聊天

## 工作原理
微信消息列表变化
↓
监听界面内容变化事件
↓
扫描所有聊天列表项
↓
提取联系人名称
↓
匹配隐藏名单
↓
执行长按操作
↓
点击"不显示该聊天"
