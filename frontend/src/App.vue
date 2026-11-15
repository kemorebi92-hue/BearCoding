<template>
  <n-config-provider :theme="null" :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup>
import { NConfigProvider, NMessageProvider, NDialogProvider } from 'naive-ui'

// 主按钮跟暖色背景走，只靠深浅区分重点，不用默认绿。
const themeOverrides = {
  common: {
    primaryColor: '#a68458',
    primaryColorHover: '#8f7048',
    primaryColorPressed: '#7a5e3c',
    primaryColorSuppl: '#f0d9aa'
  },
  Button: {
    colorPrimary: '#f5e6c4',
    colorHoverPrimary: '#f0d9aa',
    colorPressedPrimary: '#e8cfa0',
    colorFocusPrimary: '#f5e6c4',
    colorDisabledPrimary: '#f8f1e2',
    textColorPrimary: '#5a3e28',
    textColorHoverPrimary: '#4a3220',
    textColorPressedPrimary: '#3d2918',
    textColorFocusPrimary: '#5a3e28',
    textColorDisabledPrimary: 'rgba(90, 62, 40, 0.45)',
    borderPrimary: '1px solid #e6d2ae',
    borderHoverPrimary: '1px solid #dcc4a0',
    borderPressedPrimary: '1px solid #d0b48c',
    borderFocusPrimary: '1px solid #e6d2ae',
    borderDisabledPrimary: '1px solid #f0e6d4',
    rippleColorPrimary: 'rgba(240, 217, 170, 0.45)'
  }
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  height: 100%;
  width: 100%;
}

/* 【红色标注】背景图 - 请替换下面 url 中的图片地址 */
body {
  background-image: url('assets/ims.webp');
  background-size: cover;
  background-position: center;
  background-attachment: fixed;
  background-repeat: no-repeat;
}

/* 暖色遮罩层 */
.global-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 248, 225, 0.75);
  backdrop-filter: blur(2px);
  z-index: -1;
}

#app {
  height: 100%;
  position: relative;
}

.n-button--primary-type:not(.n-button--disabled) {
  background-image: linear-gradient(135deg, #f5e6c4 0%, #f0d9aa 100%);
}
</style>