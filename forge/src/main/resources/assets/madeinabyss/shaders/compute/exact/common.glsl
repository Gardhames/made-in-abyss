layout(std430,binding=0) readonly buffer Directory {uint directory[];};
layout(std430,binding=1) readonly buffer Roots {uint roots[];};
layout(std430,binding=2) readonly buffer Opacity0 {uint opacity0[];};
layout(std430,binding=3) readonly buffer Opacity1 {uint opacity1[];};
layout(std430,binding=4) readonly buffer Opacity2 {uint opacity2[];};
layout(std430,binding=5) readonly buffer HaloMeta {int haloMeta[];};
layout(std430,binding=6) readonly buffer HaloData {uint haloData[];};
layout(std430,binding=7) buffer Columns {int columns[];};
layout(std430,binding=8) buffer Fractions0 {double fractions0[];};
layout(std430,binding=9) buffer Fractions1 {double fractions1[];};
layout(std430,binding=10) buffer Flags {uint flags[];};
layout(std430,binding=11) buffer Jobs {uint jobs[];};
layout(std430,binding=12) buffer Scratch {double scratch[];};
layout(std430,binding=13) readonly buffer Owners {int owners[];};
layout(std430,binding=14) buffer Faces {int faces[];};
layout(std430,binding=15) buffer Counters {uint counters[];};
uniform ivec3 uBrickMin,uBrickMax,uPageMin,uPageSize,uGridMin,uGridSize;
uniform int uHaloMin,uHaloSide,uSky,uCoreLower,uParentLower,uWater;
uniform double uRadiusSquared;
uniform uint uCount,uJobOffset,uFractionCap,uQuantCap;
uniform ivec4 uDirections[32];
uniform double uFactors[16];
uint invocation(){return (gl_WorkGroupID.y*32768u+gl_WorkGroupID.x)*gl_WorkGroupSize.x+gl_LocalInvocationIndex;}
double radiusSquared(ivec2 p){return double(p.x)*double(p.x)+double(p.y)*double(p.y);}
int knownLower(ivec2 p){return radiusSquared(p)<uRadiusSquared?uCoreLower:uParentLower;}
bool insideXZ(ivec3 b){return b.x>=uBrickMin.x&&b.x<=uBrickMax.x&&b.z>=uBrickMin.z&&b.z<=uBrickMax.z;}
bool insideCore(ivec3 b){return insideXZ(b)&&b.y>=uBrickMin.y&&b.y<=uBrickMax.y;}
uint coreRoot(ivec3 b){ivec3 p=(b>>3)-uPageMin;uint at=directory[(p.y*uPageSize.z+p.z)*uPageSize.x+p.x];return at==0u?2u:roots[at-1u+uint(((b.y&7)<<6)|((b.z&7)<<3)|(b.x&7))];}
uint opacityWord(uint at){return at<(1u<<25)?opacity0[at]:at<(2u<<25)?opacity1[at-(1u<<25)]:opacity2[at-(2u<<25)];}
int unknown(ivec3 p){uint old=atomicAdd(counters[0],1u);if(old==0u){counters[1]=uint(p.x);counters[2]=uint(p.y);counters[3]=uint(p.z);}return -1;}
int haloTile(ivec2 p){ivec2 d=p-ivec2(uHaloMin);if(any(lessThan(d,ivec2(0)))||any(greaterThanEqual(d,ivec2(uHaloSide))))return -1;return ((d.y>>4)*(uHaloSide>>4)+(d.x>>4))*2;}
int haloTop(ivec2 p){int at=haloTile(p);if(at<0)return -2147483647;uint meta=uint(haloMeta[at+1]),bits=meta&15u;if(bits==0u)return haloMeta[at];uint bit=uint(((p.y&15)<<4)|(p.x&15))*bits,word=(meta>>4)+(bit>>5),shift=bit&31u;uint value=haloData[word]>>shift;if(shift+bits>32u)value|=haloData[word+1u]<<(32u-shift);return haloMeta[at]+int(value&((1u<<bits)-1u));}
int opacityAt(ivec3 p){if(p.y>=uSky)return 0;if(p.y<knownLower(p.xz))return unknown(p);ivec3 b=p>>4;
    if(insideCore(b)){uint root=coreRoot(b),tag=root&3u;if(tag==1u)return int((root>>2)&15u);if(tag==2u)return 15;if(tag==3u){uint i=uint(((p.y&15)<<8)|((p.z&15)<<4)|(p.x&15));return int((opacityWord((root>>2)+(i>>3))>>((i&7u)*4u))&15u);}return unknown(p);}
    if(p.y>0&&radiusSquared(p.xz)>=4000000.0)return 0;
    int top=haloTop(p.xz);if(top==-2147483647)return unknown(p);if(p.y<=top)return 15;if(p.y<=0)return uWater;return top<=11||p.y>top+8?0:unknown(p);
}
// -2 means nonuniform/unproved. No unavailable cell becomes a numeric opacity.
int uniformCube(ivec3 b){ivec3 p=b*16;if(p.y>=uSky)return 0;if(insideCore(b)){uint root=coreRoot(b),tag=root&3u;if(tag==1u)return int((root>>2)&15u);if(tag==2u)return 15;return -2;}
    if(p.y<uParentLower)return -2;
    ivec2 nearP=ivec2(p.x>0?p.x:p.x+15<0?p.x+15:0,p.z>0?p.z:p.z+15<0?p.z+15:0);
    if(p.y>0&&radiusSquared(nearP)>=4000000.0)return 0;
    if(insideXZ(b)&&radiusSquared(nearP)<uRadiusSquared)return -2;
    int tile=haloTile(p.xz);if(tile<0)return -2;uint meta=uint(haloMeta[tile+1]);int lo=haloMeta[tile],hi=lo+int((1u<<(meta&15u))-1u);
    if(p.y+15<=lo)return 15;if(p.y>hi&&p.y+15<=0)return uWater;if(p.y>max(0,hi))return 0;return -2;
}
ivec3 ownerMin(uint owner){return ivec3(owners[owner*8u],owners[owner*8u+1u],owners[owner*8u+2u]);}
ivec3 ownerMax(uint owner){return ivec3(owners[owner*8u+3u],owners[owner*8u+4u],owners[owner*8u+5u]);}
int faceCount(uint owner){return owners[owner*8u+1u]>owners[owner*8u+6u]?6:5;}
ivec3 facePoint(uint owner,int face){ivec3 lo=ownerMin(owner),hi=ownerMax(owner),p=lo+(hi-lo-1)/2;if(face==0)p.y=hi.y;else if(face==1)p.x=lo.x-1;else if(face==2)p.x=hi.x;else if(face==3)p.z=lo.z-1;else if(face==4)p.z=hi.z;else p.y=lo.y-1;return p;}
int directAt(ivec3 p){if(p.y>=uSky)return 15;ivec2 c=p.xz-uGridMin.xz;ivec2 n=uGridSize.xz*8+1;if(any(lessThan(c,ivec2(0)))||any(greaterThanEqual(c,n))){unknown(p);return 0;}int i=(c.y*n.x+c.x)*3,first=columns[i];if(p.y>first)return 15;int d=first-p.y;if(d>=15)return 0;return d<8?int((uint(columns[i+1])>>uint(d*4))&15u):int((uint(columns[i+2])>>uint((d-8)*4))&15u);}
bool openSegment(ivec3 p,ivec3 end){ivec3 len=abs(end-p),step=sign(end-p);i64vec3 next=i64vec3(1);for(int visited=0;visited<32;visited++){
    if(opacityAt(p)!=0)return false;if(all(equal(p,end)))return true;
    int axis=-1;for(int a=0;a<3;a++)if(len[a]>0&&(axis<0||next[a]*int64_t(len[axis])<next[axis]*int64_t(len[a])))axis=a;
    if(axis<0)return true;int64_t numerator=next[axis],denominator=int64_t(len[axis]);
    bvec3 cross=bvec3(false);for(int a=0;a<3;a++)cross[a]=len[a]>0&&next[a]*denominator==numerator*int64_t(len[a]);
    for(int a=0;a<3;a++)if(cross[a]){p[a]+=step[a];next[a]+=int64_t(2);}
}atomicAdd(counters[4],1u);return false;}
uint nodeIndex(ivec3 p){ivec3 c=(p-uGridMin)/8;if(any(lessThan(c,ivec3(0)))||any(greaterThanEqual(c,uGridSize))){unknown(p);return 0u;}return uint((c.y*uGridSize.z+c.z)*uGridSize.x+c.x);}
ivec3 nodePoint(uint index){int x=int(index%uint(uGridSize.x)),z=int((index/uint(uGridSize.x))%uint(uGridSize.z)),y=int(index/uint(uGridSize.x*uGridSize.z));return uGridMin+ivec3(x,y,z)*8;}
double fractionAt(ivec3 p){if(p.y>=uSky)return 1.0;uint i=nodeIndex(p);return i<(1u<<24)?fractions0[i]:fractions1[i-(1u<<24)];}
void requestFraction(ivec3 p){if(p.y>=uSky)return;uint i=nodeIndex(p),mask=1u<<(i&31u);if((atomicOr(flags[i>>5],mask)&mask)==0u){uint n=atomicAdd(counters[5],1u);if(n<uFractionCap)jobs[n]=i;else atomicAdd(counters[7],1u);}}
void requestQuant(uint owner,int face){uint n=atomicAdd(counters[6],1u);if(n<uQuantCap)jobs[uFractionCap+n]=owner*6u+uint(face);else atomicAdd(counters[7],1u);faces[owner*8u+2u+uint(face)]=-1;}
int diffuseLevel(double f){precise double n=4.0*f;precise double d=1.0+3.0*f;precise double r=15.0*(n/d);return min(14,int(floor(r+0.5)));}
double nextUp(double v){if(v==0.0)return packDouble2x32(uvec2(1,0));uvec2 b=unpackDouble2x32(v);uint64_t u=(uint64_t(b.y)<<32)|uint64_t(b.x);u=v>0.0?u+uint64_t(1):u-uint64_t(1);return packDouble2x32(uvec2(uint(u),uint(u>>32)));}
double nextDown(double v){if(v==0.0)return -packDouble2x32(uvec2(1,0));uvec2 b=unpackDouble2x32(v);uint64_t u=(uint64_t(b.y)<<32)|uint64_t(b.x);u=v>0.0?u-uint64_t(1):u+uint64_t(1);return packDouble2x32(uvec2(uint(u),uint(u>>32)));}
double nonnegativeDown(double v){return max(0.0,nextDown(v));}
int certify(double lo,double hi){if(lo==0.0&&hi==0.0)return 0;precise double nl=nonnegativeDown(4.0*lo),nh=nextUp(4.0*hi);precise double dl=nextDown(1.0+nonnegativeDown(3.0*lo)),dh=nextUp(1.0+nextUp(3.0*hi));precise double rl=nonnegativeDown(15.0*nonnegativeDown(nl/dh)),rh=nextUp(15.0*nextUp(nh/dl));int a=min(14,int(floor(rl+0.5))),b=min(14,int(floor(rh+0.5)));return a==b?a:-1;}
void advanceRay(inout ivec3 p,inout i64vec3 n,ivec4 dir){int64_t qx=int64_t(dir.x),qy=int64_t(dir.y),qz=int64_t(dir.z);int64_t xy=n.x*qy,yx=n.y*qx,xz=n.x*qz,zx=n.z*qx,yz=n.y*qz,zy=n.z*qy;
    bool ax=qx>int64_t(0)&&xy<=yx&&(qz==int64_t(0)||xz<=zx),ay=(qx==int64_t(0)||yx<=xy)&&(qz==int64_t(0)||yz<=zy),az=qz>int64_t(0)&&(qx==int64_t(0)||zx<=xz)&&zy<=yz;
    if(ax){p.x+=(dir.w&3)-1;n.x+=int64_t(2);}if(ay){p.y++;n.y+=int64_t(2);}if(az){p.z+=((dir.w>>2)&3)-1;n.z+=int64_t(2);}
}
