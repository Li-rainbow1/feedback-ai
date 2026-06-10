import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({
  breaks: true,
  gfm: true
})

export const renderMarkdown = (source?: string) => {
  const html = marked.parse(source || '', { async: false }) as string
  return DOMPurify.sanitize(html)
}

export const markdownExcerpt = (source?: string, maxLength = 100) => {
  const plainText = (source || '')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_`>]/g, '')
    .replace(/^\s*[-+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    .replace(/\s+/g, ' ')
    .trim()

  return plainText.length > maxLength
    ? `${plainText.slice(0, maxLength)}...`
    : plainText
}
