import { Children, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from './cn';

export interface ScrollListProps {
  /**
   * Nombre d'éléments. Déduit du nombre d'enfants directs si omis — passer une
   * valeur explicite si les enfants ne correspondent pas 1:1 aux éléments.
   */
  count?: number;
  /** Seuil au-delà duquel la liste devient défilante (défaut 25). */
  threshold?: number;
  /**
   * Hauteur maximale explicite (nombre = px, ou toute valeur CSS). Sinon,
   * mesurée pour afficher exactement `threshold` éléments avant le défilement ;
   * repli sur `fallbackMaxHeight` quand la mesure n'est pas disponible (SSR /
   * tests jsdom).
   */
  maxHeight?: number | string;
  /** Repli de hauteur quand la mesure échoue (défaut '22rem'). */
  fallbackMaxHeight?: string;
  className?: string;
  children: ReactNode;
}

/**
 * Conteneur de liste qui ne devient défilant qu'au-delà d'un seuil d'éléments
 * (25 par défaut). En-deçà, il se rend de façon transparente (aucun style
 * ajouté). Au-delà, il plafonne sa hauteur — mesurée pour montrer précisément
 * `threshold` éléments quelle que soit leur hauteur — et active un défilement
 * vertical avec la scrollbar custom du design system.
 *
 * Ne remplace pas la virtualisation (`useVirtualRows`) : à privilégier pour des
 * listes modérées (dizaines à quelques centaines) rendues intégralement, dont
 * on veut simplement borner l'encombrement vertical.
 */
export function ScrollList({
  count,
  threshold = 25,
  maxHeight,
  fallbackMaxHeight = '22rem',
  className,
  children,
}: ScrollListProps) {
  const ref = useRef<HTMLDivElement>(null);
  const n = count ?? Children.count(children);
  const scrollable = n > threshold;
  const [measured, setMeasured] = useState<number | undefined>(undefined);

  useLayoutEffect(() => {
    if (!scrollable || maxHeight != null) { setMeasured(undefined); return; }
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      // Hauteur des `threshold` premiers éléments = position du (threshold+1)-ème
      // relative au conteneur (gaps/marges inclus). getBoundingClientRect évite
      // les pièges d'offsetParent.
      const nth = el.children[threshold] as HTMLElement | undefined;
      if (!nth) { setMeasured(undefined); return; }
      const top = nth.getBoundingClientRect().top - el.getBoundingClientRect().top + el.scrollTop;
      setMeasured(top > 0 ? top : undefined);
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [scrollable, threshold, maxHeight, n]);

  if (!scrollable) {
    // Rendu transparent sous le seuil : ni hauteur bornée, ni scrollbar.
    return <div ref={ref} className={className}>{children}</div>;
  }

  const resolved = maxHeight != null
    ? (typeof maxHeight === 'number' ? `${maxHeight}px` : maxHeight)
    : (measured != null ? `${measured}px` : fallbackMaxHeight);

  return (
    <div ref={ref} style={{ maxHeight: resolved }} className={cn('overflow-y-auto custom-scrollbar', className)}>
      {children}
    </div>
  );
}

export default ScrollList;
