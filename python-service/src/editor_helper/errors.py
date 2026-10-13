class ServiceError(Exception):
    def __init__(self, code, message, status=400, retryable=False):
        super().__init__(message)
        self.code, self.status, self.retryable = code, status, retryable
