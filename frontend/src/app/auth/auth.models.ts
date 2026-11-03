export interface AuthUser { id: string; name: string; email: string; role: string; }
export interface AuthResponse { accessToken: string; user: AuthUser; }
