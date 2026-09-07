// Use for reads only. Ignoring a response does not cancel a remote mutation.
export function latestRequest() {
  let generation = 0
  return {
    begin() {
      const mine = ++generation
      return () => mine === generation
    },
    invalidate() { generation++ },
  }
}
