import test from 'node:test'
import assert from 'node:assert/strict'
import { beginApplicationCreation, readApplicationCreation, completeApplicationCreation } from './applicationCreationRequest.ts'

const payload = {name:'Retry app',code:'retry-app',serverId:'123',environment:'PRODUCTION',
  description:'由自动接入向导配置',containerSnapshotId:null,currentVersion:'',accessUrl:'',healthCheckUrl:'http://127.0.0.1:18081/health'}
function storage() {
  const data = new Map()
  return {getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)}
}
test('application creation retries retain exact non-secret parameters and remain owner scoped', () => {
  const store=storage(), first=beginApplicationCreation('1',payload,store)
  assert.deepEqual(readApplicationCreation('1',store),first)
  assert.deepEqual(beginApplicationCreation('1',{...payload},store),first)
  const second=beginApplicationCreation('2',payload,store)
  assert.notEqual(first.requestId,second.requestId)
  for (const [key,value] of [['name','Changed'],['code','changed'],['serverId','456'],['healthCheckUrl','http://127.0.0.1:19001/health']])
    assert.throws(()=>beginApplicationCreation('1',{...payload,[key]:value},store),/未完成/)
  completeApplicationCreation('1',second.requestId,store)
  assert.deepEqual(readApplicationCreation('1',store),first)
  completeApplicationCreation('1',first.requestId,store)
  assert.equal(readApplicationCreation('1',store),null)
  assert.deepEqual(readApplicationCreation('2',store),second)
})
test('invalid or unavailable storage and secret-bearing payloads fail before a new request', () => {
  assert.throws(()=>beginApplicationCreation(undefined,payload,storage()),/登录/)
  const broken={getItem:()=>'{bad',setItem:()=>assert.fail('must not overwrite'),removeItem:()=>{}}
  assert.throws(()=>beginApplicationCreation('1',payload,broken))
  const unavailable={getItem:()=>null,setItem:()=>{throw Error('storage unavailable')},removeItem:()=>{}}
  assert.throws(()=>beginApplicationCreation('1',payload,unavailable),/storage unavailable/)
  for (const unsafe of [{...payload,repositoryToken:'synthetic-only'}, {...payload,healthCheckUrl:'http://127.0.0.1:18081/health?token=synthetic'}, {...payload,accessUrl:'https://user:pass@example.com'}])
    assert.throws(()=>beginApplicationCreation('1',unsafe,storage()),/无效/)
  for (const port of [80,65536]) assert.throws(()=>beginApplicationCreation('1',{...payload,healthCheckUrl:`http://127.0.0.1:${port}/health`},storage()))
})
