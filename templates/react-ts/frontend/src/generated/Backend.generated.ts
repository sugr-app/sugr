// Placeholder - overwritten by the real file generated from Backend.java's @Bind
// methods once you build the Java side (see lib/build/generated/sources/...).
import { invoke } from '@sugr/runtime'

export const Backend = {
  hello(name: string): Promise<string> {
    return invoke('hello', [name])
  },
}
