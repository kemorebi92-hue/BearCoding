/**
 * 浏览文件夹失败时的用户提示。纯函数，便于单测。
 * @param {any} error axios/error-like
 * @returns {string}
 */
export function formatPickDirectoryError(error) {
  const status = error?.response?.status
  if (status === 404 || status === 501 || status === 405) {
    return '请手动填写路径（当前后端不支持浏览文件夹）'
  }
  const bodyErr = error?.response?.data?.error
  if (bodyErr) return String(bodyErr)
  if (error?.code === 'ECONNABORTED') return '等待选择文件夹超时，请再试一次'
  if (error?.message) return String(error.message)
  return '后端没开或请求失败'
}
