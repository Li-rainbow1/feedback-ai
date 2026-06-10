import { ref } from 'vue'
import api from '../api'

export const user = ref<any>(JSON.parse(localStorage.getItem('user') || 'null'))
export const selectedProductId = ref<number | null>(Number(localStorage.getItem('selectedProductId')) || null)
export const products = ref<any[]>([])

export async function loadProducts() {
  try {
    const res = await api.get('/products')
    products.value = res.data
    if (!selectedProductId.value && products.value.length > 0) selectedProductId.value = products.value[0].id
  } catch (e) { console.error(e) }
}

export function selectedProduct() {
  return products.value.find((p: any) => p.id === selectedProductId.value)
}

export function getUser() { return user.value }
