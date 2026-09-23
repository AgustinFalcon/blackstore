/**
 * AssistTime-style API envelope (mirrors backend BaseResponse).
 */
export interface BaseResponse<T> {
  code: number;
  traceId: string;
  data: T | null;
  message: string | null;
  errorCode: string | null;
  retryable: boolean | null;
}

export function isSuccessResponse<T>(response: BaseResponse<T>): response is BaseResponse<T> & { data: T } {
  return response.code === 200 && response.data !== null && response.errorCode === null;
}
